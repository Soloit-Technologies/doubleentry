package com.soloit.doubleentry.sync

import android.util.Log
import com.soloit.doubleentry.data.SyncTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes

data class SyncStatus(
    /** Feature flag on and Supabase configured for this build. */
    val available: Boolean,
    val signedInEmail: String?,
    val enabled: Boolean,
    val isSyncing: Boolean = false,
    val lastError: String? = null,
    val lastSyncAtMillis: Long? = null,
) {
    val isSignedIn: Boolean get() = signedInEmail != null
    val isActive: Boolean get() = available && enabled && isSignedIn
}

/**
 * Coordinates cloud sync: coalesces push requests, runs pull-then-push cycles one at a time,
 * keeps the session fresh and, while the app is in the foreground, listens to Realtime events
 * with a 15-minute polling fallback.
 */
class SyncManager(
    private val config: SupabaseConfig,
    private val prefs: SyncPreferences,
    private val api: SupabaseApi,
    private val engine: SyncEngine,
    private val scope: CoroutineScope,
) : SyncTrigger, AccessTokenProvider {

    private val _status = MutableStateFlow(snapshot())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val gate = Mutex()
    private val refreshLock = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val realtime = RealtimeClient(config, api.http, this) { requestSync() }

    @Volatile private var inForeground = false
    private var foregroundJob: Job? = null

    init {
        // Requests arriving while a sync runs are conflated into a single follow-up run.
        scope.launch { for (request in requests) runSync() }
    }

    private val canSync: Boolean get() = config.isAvailable && prefs.enabled && prefs.userId != null

    override fun requestSync() {
        if (canSync) requests.trySend(Unit)
    }

    /** Runs a full pull + push now and waits for it. Returns false when it failed. */
    suspend fun syncNow(): Boolean {
        runSync()
        return _status.value.lastError == null
    }

    /** Pulls remote changes only. Returns true when local rows changed. */
    suspend fun pullIfNewer(): Boolean {
        if (!canSync) return false
        return gate.withLock {
            setSyncing(true)
            try {
                engine.pullAll(this).also { _status.update { s -> s.copy(lastError = null) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
                false
            } finally {
                setSyncing(false)
            }
        }
    }

    private suspend fun runSync() {
        if (!canSync) return
        gate.withLock {
            setSyncing(true)
            try {
                // Pull first so concurrent remote edits win and stale rows are not pushed.
                engine.pullAll(this)
                engine.pushAll(this)
                prefs.lastSyncAtMillis = System.currentTimeMillis()
                _status.update { it.copy(lastError = null, lastSyncAtMillis = prefs.lastSyncAtMillis) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                setSyncing(false)
            }
        }
    }

    /** Signs in, performs the initial full pull, then pushes any local rows. */
    suspend fun signIn(email: String, password: String) {
        val token = api.signIn(email.trim(), password)
        if (token.user?.id.isNullOrBlank() || token.accessToken.isNullOrBlank()) {
            throw SyncException("We couldn't sign you in. Please try again.")
        }
        storeTokens(token, email.trim())
        prefs.enabled = true
        publish()
        // The initial pull must finish before anything is pushed, otherwise children could be
        // pushed before their parents exist remotely.
        pullIfNewer()
        requestSync()
        if (inForeground) startForegroundWork()
    }

    /** Stops syncing and forgets the session. Local data is kept. */
    suspend fun signOut() {
        stopForegroundWork()
        gate.withLock {
            prefs.clearSession()
            prefs.enabled = false
        }
        publish()
    }

    fun setEnabled(enabled: Boolean) {
        prefs.enabled = enabled
        publish()
        if (enabled) {
            requestSync()
            if (inForeground) startForegroundWork()
        } else {
            stopForegroundWork()
        }
    }

    fun onForeground() {
        inForeground = true
        if (canSync) {
            requestSync()
            startForegroundWork()
        }
    }

    fun onBackground() {
        inForeground = false
        stopForegroundWork()
    }

    @Synchronized
    private fun startForegroundWork() {
        if (!canSync || foregroundJob?.isActive == true) return
        foregroundJob = scope.launch {
            launch { realtime.run() }
            while (isActive) {
                delay(15.minutes)
                requestSync()
            }
        }
    }

    @Synchronized
    private fun stopForegroundWork() {
        foregroundJob?.cancel()
        foregroundJob = null
    }

    override suspend fun accessToken(forceRefresh: Boolean): String = refreshLock.withLock {
        val current = prefs.accessToken
        val expiresAt = prefs.accessTokenExpiresAtMillis
        if (!forceRefresh && !current.isNullOrBlank() && (expiresAt == null || expiresAt > System.currentTimeMillis() + 30_000)) {
            return@withLock current
        }
        // Refresh tokens are single-use, hence the lock: concurrent refreshes would revoke the session.
        val refresh = prefs.refreshToken ?: throw SyncException("Your session has expired. Please sign in again.")
        val refreshed = api.refresh(refresh)
        storeTokens(refreshed, prefs.userEmail)
        refreshed.accessToken ?: throw SyncException("Your session has expired. Please sign in again.")
    }

    private fun storeTokens(token: AuthTokenResponse, email: String?) {
        prefs.accessToken = token.accessToken
        token.refreshToken?.let { prefs.refreshToken = it }
        prefs.accessTokenExpiresAtMillis = if (token.expiresIn > 0) System.currentTimeMillis() + token.expiresIn * 1000 else null
        token.user?.id?.takeIf { it.isNotBlank() }?.let { prefs.userId = it }
        if (!email.isNullOrBlank()) prefs.userEmail = email
    }

    private fun fail(e: Exception) {
        Log.w(TAG, "Sync failed", e)
        _status.update { it.copy(lastError = SupabaseApi.userMessage(e)) }
    }

    private fun setSyncing(value: Boolean) = _status.update { it.copy(isSyncing = value) }

    private fun publish() = _status.update {
        snapshot().copy(isSyncing = it.isSyncing, lastError = if (prefs.userId == null) null else it.lastError)
    }

    private fun snapshot() = SyncStatus(
        available = config.isAvailable,
        signedInEmail = prefs.userId?.let { prefs.userEmail ?: "Signed in" },
        enabled = prefs.enabled,
        lastSyncAtMillis = prefs.lastSyncAtMillis,
    )

    private companion object {
        const val TAG = "SyncManager"
    }
}
