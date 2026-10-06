package com.soloit.doubleentry.sync

import android.util.Log
import com.soloit.doubleentry.data.SyncTrigger
import com.soloit.doubleentry.data.local.nowMicros
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
    /** The cloud sync feature is switched on for this build (`cloudSync.enabled`). */
    val featureEnabled: Boolean,
    /** A Supabase URL and key were compiled into this build. */
    val configured: Boolean,
    val signedInEmail: String?,
    val enabled: Boolean,
    val isSyncing: Boolean = false,
    val lastError: String? = null,
    /** The last failure was only a missing connection; changes wait and upload later. */
    val offline: Boolean = false,
    /** The session can't be renewed: nothing syncs until the user signs in again. */
    val needsSignIn: Boolean = false,
    val lastSyncAtMillis: Long? = null,
    /** Local changes (a transaction with its lines counts once) that haven't reached the cloud yet. */
    val pendingChanges: Int = 0,
    /** Unsynced edits on this device that lost to newer edits from another device, until dismissed. */
    val replacedChanges: Int = 0,
    val replacedAtMillis: Long? = null,
    /** The account this device's data belongs to, also while signed out; null when it never synced. */
    val dataOwnerEmail: String? = null,
) {
    /** Feature flag on and Supabase configured: signing in is possible. */
    val available: Boolean get() = featureEnabled && configured
    val isSignedIn: Boolean get() = signedInEmail != null
    val isActive: Boolean get() = available && enabled && isSignedIn

    /** Something the user should know about even when they're not looking at the sync screen. */
    val attention: SyncAttention?
        get() = when {
            !available || !isSignedIn -> null
            needsSignIn -> SyncAttention.SignInAgain
            !enabled && pendingChanges > 0 -> SyncAttention.Paused
            lastError != null && pendingChanges > 0 -> if (offline) SyncAttention.Offline else SyncAttention.Failing
            replacedChanges > 0 -> SyncAttention.Replaced
            else -> null
        }
}

enum class SyncAttention { SignInAgain, Failing, Offline, Paused, Replaced }

/** A decision signing in needs from the user before anything syncs. */
sealed interface SignInQuestion {
    /** Back to the account this device synced with, but changes were made here while signed out. */
    data class ChangesWhileSignedOut(val email: String, val changes: Int, val deletions: Int) : SignInQuestion

    /** This device's ledger never synced, and the account has one too; merging them would duplicate everything. */
    data class TwoLedgers(val email: String) : SignInQuestion

    /**
     * This device holds the ledger of another account ([previousEmail]), which keeps its own copy
     * in the cloud either way. [SignInChoice.UseThisDevice] copies it to [email] (replacing the
     * ledger there when [accountHasData]); [SignInChoice.UseCloud] switches to [email]'s ledger, or
     * an empty one. [unsyncedChanges] never reached the other account and are only kept by copying.
     */
    data class OtherAccount(
        val email: String,
        val previousEmail: String?,
        val accountHasData: Boolean,
        val unsyncedChanges: Int,
    ) : SignInQuestion
}

enum class SignInChoice {
    /** Keep this device's data: upload its changes, or let its ledger replace the account's. */
    UseThisDevice,

    /** Discard this device's data and download the account's. */
    UseCloud,
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
    pendingChanges: Flow<Int>,
) : SyncTrigger, AccessTokenProvider {

    private val _status = MutableStateFlow(snapshot())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val gate = Mutex()
    private val refreshLock = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val realtime = RealtimeClient(config, api.http, this) { requestSync() }

    @Volatile private var inForeground = false
    private var foregroundJob: Job? = null

    /** An authenticated session waiting for the answer to a [SignInQuestion]; nothing is stored yet. */
    private class PendingSignIn(val token: AuthTokenResponse, val email: String, val sameOwner: Boolean, val cloudHasData: Boolean)

    @Volatile private var pendingSignIn: PendingSignIn? = null

    init {
        // Installs that signed in before the data owner was tracked.
        if (prefs.userId != null && prefs.dataOwnerId == null) {
            prefs.dataOwnerId = prefs.userId
            prefs.dataOwnerEmail = prefs.userEmail
            publish()
        }
        // Requests arriving while a sync runs are conflated into a single follow-up run.
        scope.launch { for (request in requests) runSync() }
        scope.launch { pendingChanges.collect { count -> _status.update { it.copy(pendingChanges = count) } } }
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
                applyPendingCloudWipe()
                val result = engine.pullAll(this)
                recordReplaced(result)
                succeeded()
                result.changed
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
                applyPendingCloudWipe()
                // Pull first so concurrent remote edits win and stale rows are not pushed.
                recordReplaced(engine.pullAll(this))
                engine.pushAll(this)
                prefs.lastSyncAtMillis = System.currentTimeMillis()
                succeeded()
                _status.update { it.copy(lastSyncAtMillis = prefs.lastSyncAtMillis) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e)
            } finally {
                setSyncing(false)
            }
        }
    }

    /**
     * Signs in. When this device's data and the account's could conflict, returns a question and
     * waits for [completeSignIn] (or [cancelSignIn]); otherwise syncs right away and returns null.
     */
    suspend fun signIn(email: String, password: String): SignInQuestion? {
        val address = email.trim()
        val token = api.signIn(address, password)
        val userId = token.user?.id
        val accessToken = token.accessToken
        if (userId.isNullOrBlank() || accessToken.isNullOrBlank()) {
            throw SyncException("We couldn't sign you in. Please try again.")
        }
        val current = prefs.userId
        if (current != null) {
            // Renewing an expired session: the data on this device already belongs to the account.
            if (current != userId) throw SyncException("That is a different account. Sign out first to switch accounts.")
            storeTokens(token, address)
            publish()
            runSync()
            if (inForeground) startForegroundWork()
            return null
        }

        val local = engine.localSummary()
        val owner = prefs.dataOwnerId
        val sameOwner = owner == userId
        val cloudHasData = engine.cloudHasData { accessToken }
        val pending = PendingSignIn(token, address, sameOwner, cloudHasData)
        val question = when {
            // Includes deletions made while signed out: uploading them deletes data on every device.
            sameOwner -> if (local.unsyncedChanges > 0) {
                SignInQuestion.ChangesWhileSignedOut(address, local.unsyncedChanges, local.unsyncedDeletions)
            } else {
                null
            }
            owner != null -> if (local.hasData || local.unsyncedChanges > 0) {
                SignInQuestion.OtherAccount(address, prefs.dataOwnerEmail, cloudHasData, local.unsyncedChanges)
            } else {
                null
            }
            local.hasData && cloudHasData -> SignInQuestion.TwoLedgers(address)
            else -> null
        }
        if (question != null) {
            pendingSignIn = pending
            return question
        }
        // No conflict. A ledger that never synced goes up as it is; otherwise this device takes
        // the account's data (dropping leftovers such as another account's tombstones).
        val choice = if (sameOwner || (owner == null && (local.hasData || !cloudHasData))) SignInChoice.UseThisDevice else SignInChoice.UseCloud
        finishSignIn(pending, choice)
        return null
    }

    /** Answers the [SignInQuestion] returned by [signIn] and starts syncing. */
    suspend fun completeSignIn(choice: SignInChoice) {
        val pending = pendingSignIn ?: throw SyncException("Please sign in again.")
        pendingSignIn = null
        finishSignIn(pending, choice)
    }

    fun cancelSignIn() {
        pendingSignIn = null
    }

    private suspend fun finishSignIn(pending: PendingSignIn, choice: SignInChoice) {
        gate.withLock {
            when {
                choice == SignInChoice.UseCloud -> {
                    engine.wipeLocal()
                    prefs.cloudWipeBeforeMicros = null
                }
                // Same account: upload the changes as they are, including a pending "delete all".
                pending.sameOwner -> Unit
                else -> {
                    // A ledger from another account, or one that never synced: send everything, since
                    // rows already synced elsewhere are clean. Ids are per user on the server, so the
                    // other account's copy is untouched.
                    engine.markAllForUpload()
                    // This ledger replaces the account's, so the account's rows are deleted first.
                    prefs.cloudWipeBeforeMicros = if (pending.cloudHasData) nowMicros() else null
                }
            }
            storeTokens(pending.token, pending.email)
            prefs.dataOwnerId = prefs.userId
            prefs.dataOwnerEmail = pending.email
            prefs.enabled = true
        }
        publish()
        // One full cycle: any cloud wipe first, then the initial pull, then the push (parents
        // must exist remotely before their children are pushed).
        runSync()
        if (inForeground) startForegroundWork()
    }

    /**
     * Call after this device replaced its whole ledger (delete all, import) at [beforeMicros]:
     * cloud rows from before then are deleted on the next sync, including ones never pulled here.
     * While signed out the request waits, and the next sign-in asks whether to apply it.
     */
    fun replaceCloudLedger(beforeMicros: Long) {
        if (prefs.dataOwnerId == null) return
        prefs.cloudWipeBeforeMicros = beforeMicros
        requestSync()
    }

    /** Before a device reset: tries to upload everything, then returns how many changes would still be lost. */
    suspend fun uploadBeforeReset(): Int {
        if (canSync && !_status.value.needsSignIn) runSync()
        return engine.localSummary().unsyncedChanges
    }

    /** Signs out and deletes this device's data without touching the cloud. */
    suspend fun resetDevice() {
        signOut()
        gate.withLock {
            engine.wipeLocal()
            prefs.dataOwnerId = null
            prefs.dataOwnerEmail = null
            prefs.cloudWipeBeforeMicros = null
        }
        publish()
    }

    /** Renews an expired session for the account that is already signed in. */
    suspend fun signInAgain(password: String) {
        val email = prefs.userEmail ?: throw SyncException("Please sign out and sign in again.")
        signIn(email, password)
    }

    /** Hides the notice about changes replaced by another device. */
    fun dismissReplaced() {
        prefs.clearReplacedChanges()
        _status.update { it.copy(replacedChanges = 0, replacedAtMillis = null) }
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
        val refresh = prefs.refreshToken ?: throw SessionExpiredException()
        val refreshed = api.refresh(refresh)
        storeTokens(refreshed, prefs.userEmail)
        refreshed.accessToken ?: throw SessionExpiredException()
    }

    private fun storeTokens(token: AuthTokenResponse, email: String?) {
        prefs.accessToken = token.accessToken
        token.refreshToken?.let { prefs.refreshToken = it }
        prefs.accessTokenExpiresAtMillis = if (token.expiresIn > 0) System.currentTimeMillis() + token.expiresIn * 1000 else null
        token.user?.id?.takeIf { it.isNotBlank() }?.let { prefs.userId = it }
        if (!email.isNullOrBlank()) prefs.userEmail = email
    }

    /** Runs a pending [replaceCloudLedger]: deletes the old cloud rows, then uploads this device's at once. */
    private suspend fun applyPendingCloudWipe() {
        val before = prefs.cloudWipeBeforeMicros ?: return
        engine.wipeCloudBefore(before, this)
        // Push before pulling: rows sharing ids with the deleted ones (settings, default accounts)
        // must overwrite those tombstones rather than lose to them.
        engine.pushAll(this)
        prefs.cloudWipeBeforeMicros = null
    }

    private fun fail(e: Exception) {
        Log.w(TAG, "Sync failed", e)
        _status.update {
            it.copy(lastError = SupabaseApi.userMessage(e), offline = e is OfflineException, needsSignIn = e is SessionExpiredException)
        }
    }

    private fun succeeded() = _status.update { it.copy(lastError = null, offline = false, needsSignIn = false) }

    private fun recordReplaced(result: PullResult) {
        if (result.replacedLocalChanges == 0) return
        prefs.addReplacedChanges(result.replacedLocalChanges, System.currentTimeMillis())
        _status.update { it.copy(replacedChanges = prefs.replacedChanges, replacedAtMillis = prefs.replacedAtMillis) }
    }

    private fun setSyncing(value: Boolean) = _status.update { it.copy(isSyncing = value) }

    private fun publish() = _status.update {
        val base = snapshot().copy(isSyncing = it.isSyncing, pendingChanges = it.pendingChanges)
        if (prefs.userId == null) base else base.copy(lastError = it.lastError, offline = it.offline, needsSignIn = it.needsSignIn)
    }

    private fun snapshot() = SyncStatus(
        featureEnabled = config.featureEnabled,
        configured = config.isConfigured,
        signedInEmail = prefs.userId?.let { prefs.userEmail ?: "Signed in" },
        enabled = prefs.enabled,
        lastSyncAtMillis = prefs.lastSyncAtMillis,
        replacedChanges = prefs.replacedChanges,
        replacedAtMillis = prefs.replacedAtMillis,
        dataOwnerEmail = prefs.dataOwnerId?.let { prefs.dataOwnerEmail },
    )

    private companion object {
        const val TAG = "SyncManager"
    }
}
