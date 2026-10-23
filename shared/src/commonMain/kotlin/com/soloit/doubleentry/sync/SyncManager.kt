package com.soloit.doubleentry.sync

import com.soloit.doubleentry.data.SyncTrigger
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.platform.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes
import java.util.concurrent.atomic.AtomicLong

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
    /** The signed-in account's backup subscription; null while signed out or not checked yet. */
    val access: CloudAccess? = null,
    /** The account signs in with Google, so an expired session is renewed through Google, not a password. */
    val signedInWithGoogle: Boolean = false,
    /** The ledger open on this device is someone else's, shared with this account. */
    val sharedLedger: Boolean = false,
    /** For a [sharedLedger]: this account's role in it and whether it may upload; null until checked. */
    val ledgerAccess: LedgerAccess? = null,
    /** The account's ledgers as last listed by the server. */
    val ledgers: List<LedgerInfo> = emptyList(),
    /** The id of the ledger open on this device; null while signed out or before it's known. */
    val ledgerId: String? = null,
    /** The server has stopped sync (for every app, or for this build); changes wait on this device. */
    val serviceBlock: ServiceBlock? = null,
    /** The ledger has used its cloud storage: the last upload was refused, and changes wait on this device. */
    val ledgerFull: Boolean = false,
) {
    /** Feature flag on and Supabase configured: signing in is possible. */
    val available: Boolean get() = featureEnabled && configured
    val isSignedIn: Boolean get() = signedInEmail != null
    val isActive: Boolean get() = available && enabled && isSignedIn

    /**
     * Signed in, but changes stay on this device: the account has no active backup subscription, or
     * for a [sharedLedger], its owner hasn't.
     */
    val notBackedUp: Boolean
        get() = isSignedIn && if (sharedLedger) ledgerAccess?.let { it.role != null && !it.writable } == true else access?.active == false

    /** The account was removed from the [sharedLedger] open on this device (or the ledger was deleted). */
    val lostLedgerAccess: Boolean get() = isSignedIn && sharedLedger && ledgerAccess != null && ledgerAccess.role == null

    /** The open ledger as listed, when known. */
    val ledger: LedgerInfo? get() = ledgers.firstOrNull { it.id == ledgerId }

    /** Something the user should know about even when they're not looking at the sync screen. */
    val attention: SyncAttention?
        get() = when {
            !available || !isSignedIn -> null
            serviceBlock != null -> if (serviceBlock.updateRequired) SyncAttention.UpdateRequired else SyncAttention.ServicePaused
            needsSignIn -> SyncAttention.SignInAgain
            lostLedgerAccess -> SyncAttention.LostAccess
            notBackedUp && pendingChanges > 0 -> SyncAttention.NotBackedUp
            ledgerFull && pendingChanges > 0 -> SyncAttention.LedgerFull
            !enabled && pendingChanges > 0 -> SyncAttention.Paused
            lastError != null && pendingChanges > 0 -> if (offline) SyncAttention.Offline else SyncAttention.Failing
            replacedChanges > 0 -> SyncAttention.Replaced
            else -> null
        }
}

enum class SyncAttention { UpdateRequired, ServicePaused, SignInAgain, LostAccess, NotBackedUp, LedgerFull, Failing, Offline, Paused, Replaced }

/** Why the server stopped sync ([SyncControl]): [message] is written for the user. */
data class ServiceBlock(val updateRequired: Boolean, val message: String) {
    companion object {
        fun of(control: SyncControl, appVersionCode: Int): ServiceBlock? = when {
            appVersionCode < control.minVersionCode -> ServiceBlock(
                updateRequired = true,
                control.message ?: "Update DoubleEntry to keep syncing. Until then, changes stay on this device.",
            )
            control.paused -> ServiceBlock(
                updateRequired = false,
                control.message ?: "Cloud sync is paused for maintenance. Changes stay on this device and upload when it's back.",
            )
            else -> null
        }
    }
}

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
 *
 * Downloading works for every signed-in account; uploading needs an active backup subscription
 * ([CloudAccess]), which the server enforces and this class checks at the start of each cycle. For
 * a ledger shared with the account, it's the owner's subscription that counts ([LedgerAccess]).
 *
 * It syncs the ledger open on this device; [use] switches to another ledger's database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncManager(
    private val config: SupabaseConfig,
    prefs: SyncPreferences,
    private val api: SupabaseApi,
    engine: SyncEngine,
    private val scope: CoroutineScope,
    pendingChanges: Flow<Int>,
) : SyncTrigger, AccessTokenProvider {
    // The open ledger's state and database; swapped by [use] while holding [gate].
    @Volatile private var prefs = prefs
    @Volatile private var engine = engine
    private val pendingChanges = MutableStateFlow(pendingChanges)


    private val _status = MutableStateFlow(snapshot())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val gate = Mutex()
    private val refreshLock = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** When the oldest sync request still waiting was made, 0 when none waits; for [SyncTiming]. */
    private val requestedAt = AtomicLong(0)
    private val realtime = RealtimeClient(config, api.http, this, ledgerId = { prefs.ledgerId }) { requestSync() }

    @Volatile private var inForeground = false
    private var foregroundJob: Job? = null

    /** An authenticated session waiting for the answer to a [SignInQuestion]; nothing is stored yet. */
    private class PendingSignIn(
        val token: AuthTokenResponse,
        val email: String,
        val google: Boolean,
        val ledgerId: String,
        val sameOwner: Boolean,
        val cloudHasData: Boolean,
    )

    @Volatile private var pendingSignIn: PendingSignIn? = null

    /** When each error was last reported, so a failure repeating every cycle is sent once in a while. */
    private val reported = mutableMapOf<String, Long>()

    init {
        // Installs that signed in before the data owner was tracked.
        if (prefs.userId != null && prefs.dataOwnerId == null) {
            prefs.dataOwnerId = prefs.userId
            prefs.dataOwnerEmail = prefs.userEmail
            publish()
        }
        // Requests arriving while a sync runs are conflated into a single follow-up run.
        scope.launch { for (request in requests) runSync() }
        scope.launch { this@SyncManager.pendingChanges.flatMapLatest { it }.collect { count -> _status.update { it.copy(pendingChanges = count) } } }
    }

    /**
     * Syncs another ledger's database from now on: [prefs] is that ledger's state, [engine] syncs its
     * database, and [pendingChanges] counts its unsynced changes. Realtime reconnects for it.
     */
    suspend fun use(prefs: SyncPreferences, engine: SyncEngine, pendingChanges: Flow<Int>) {
        stopForegroundWork()
        gate.withLock {
            this.prefs = prefs
            this.engine = engine
            this.pendingChanges.value = pendingChanges
        }
        publish()
        if (inForeground) startForegroundWork()
    }

    private val canSync: Boolean get() = config.isAvailable && prefs.enabled && prefs.userId != null

    private val canUpload: Boolean
        get() = if (prefs.ledgerSlot != null) prefs.ledgerAccess?.writable == true else prefs.cloudAccess?.active == true

    /** Removed from the shared ledger open on this device: nothing of it can be read or written any more. */
    private val lostAccess: Boolean get() = prefs.ledgerSlot != null && prefs.ledgerAccess?.let { it.role == null } == true

    /** OAuth web client id for Sign in with Google; empty when this build has none. */
    val googleClientId: String get() = config.googleClientId

    /** The signed-in account's id; Google Play purchases are tagged with it. */
    val userId: String? get() = prefs.userId

    override fun requestSync() {
        if (!canSync) return
        requestedAt.compareAndSet(0, System.currentTimeMillis())
        requests.trySend(Unit)
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
                if (serviceBlocked()) return@withLock false
                ensureLedger()
                if (lostAccess) return@withLock false
                // A ledger waiting to replace the cloud's mustn't be merged with it by a pull.
                if (prefs.cloudWipeBeforeMicros != null && !canUpload) return@withLock false
                applyPendingCloudWipe()
                val result = engine.pullAll(this)
                recordReplaced(result.replacedLocalChanges)
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
            val timing = SyncTiming(requestedAt.getAndSet(0))
            try {
                if (serviceBlocked()) return@withLock
                ensureLedger()
                val upload = refreshAccess()
                timing.step("checks")
                if (lostAccess) {
                    succeeded()
                    return@withLock
                }
                // Without a subscription a ledger waiting to replace the cloud's waits whole: a
                // pull would merge the two.
                if (!upload && prefs.cloudWipeBeforeMicros != null) {
                    succeeded()
                    return@withLock
                }
                if (upload) applyPendingCloudWipe()
                // Pull first so later edits from other devices replace local rows before they are
                // uploaded; the server refuses any that still arrive late, and push takes those.
                recordReplaced(engine.pullAll(this).replacedLocalChanges)
                timing.step("pull")
                if (upload) {
                    recordReplaced(engine.pushAll(this).replacedLocalChanges)
                    timing.step("push")
                    prefs.lastSyncAtMillis = System.currentTimeMillis()
                    _status.update { it.copy(ledgerFull = false) }
                }
                succeeded()
                _status.update { it.copy(lastSyncAtMillis = prefs.lastSyncAtMillis) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CloudAccessRequiredException) {
                // The subscription ended since the check: same as having none.
                storeAccess(CloudAccess(active = false))
                succeeded()
            } catch (e: LedgerFullException) {
                // Not a failure to retry or report: uploads resume once the ledger has room.
                Log.i(TAG, "Ledger full", e)
                _status.update { it.copy(ledgerFull = true) }
                succeeded()
            } catch (e: Exception) {
                fail(e)
            } finally {
                setSyncing(false)
                Log.d(TAG, timing.toString())
            }
        }
    }

    /**
     * Signs in with email and password, for accounts made by hand in the dashboard (everyone else
     * signs in with Google). When this device's data and the account's could conflict, returns a question
     * and waits for [completeSignIn] (or [cancelSignIn]); otherwise syncs right away and returns null.
     */
    suspend fun signIn(email: String, password: String): SignInQuestion? {
        val address = email.trim()
        return signInWith(api.signIn(address, password), address)
    }

    /**
     * Signs in with a Google ID token (from Credential Manager), creating the account the first
     * time; otherwise like [signIn]. [nonce] is the raw nonce the token was requested with.
     */
    suspend fun signInWithGoogle(idToken: String, nonce: String, email: String): SignInQuestion? {
        val session = api.signInWithGoogle(idToken, nonce)
        return signInWith(session, session.user?.email ?: email, google = true)
    }

    /** Where a browser sign-in with Google starts (desktop); it returns to [redirectTo] with a code for [signInWithGoogleCode]. */
    fun googleSignInUrl(redirectTo: String, codeChallenge: String): String = api.authorizeUrl("google", redirectTo, codeChallenge)

    /** Signs in with the code a browser sign-in with Google returned, and the PKCE verifier it was started with; otherwise like [signIn]. */
    suspend fun signInWithGoogleCode(authCode: String, codeVerifier: String): SignInQuestion? {
        val session = api.exchangeCode(authCode, codeVerifier)
        return signInWith(session, session.user?.email.orEmpty(), google = true)
    }

    private suspend fun signInWith(token: AuthTokenResponse, address: String, google: Boolean = false): SignInQuestion? {
        val userId = token.user?.id
        val accessToken = token.accessToken
        if (userId.isNullOrBlank() || accessToken.isNullOrBlank()) {
            throw SyncException("We couldn't sign you in. Please try again.")
        }
        // Signing in may wipe this device to download the cloud's ledger, which mustn't happen
        // while sync can't run.
        if (serviceBlocked()) throw SyncException(_status.value.serviceBlock!!.message)
        val current = prefs.userId
        if (current != null) {
            // Renewing an expired session: the data on this device already belongs to the account.
            if (current != userId) throw SyncException("That is a different account. Sign out first to switch accounts.")
            storeTokens(token, address)
            prefs.signedInWithGoogle = google
            publish()
            runSync()
            if (inForeground) startForegroundWork()
            return null
        }

        val local = engine.localSummary()
        val owner = prefs.dataOwnerId
        val sameOwner = owner == userId
        val ledgerId = api.ownLedger(accessToken)
        val cloudHasData = engine.cloudHasData({ accessToken }, ledgerId)
        val pending = PendingSignIn(token, address, google, ledgerId, sameOwner, cloudHasData)
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
                    // rows already synced elsewhere are clean. Ids are per ledger on the server, so the
                    // other account's copy is untouched.
                    engine.markAllForUpload()
                    // This ledger replaces the account's, so the account's rows are deleted first.
                    // Local rows count as edited at this moment, so they win over the deleted ones.
                    prefs.cloudWipeBeforeMicros = if (pending.cloudHasData) nowMicros() else null
                }
            }
            storeTokens(pending.token, pending.email)
            prefs.ledgerId = pending.ledgerId
            prefs.signedInWithGoogle = pending.google
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
     * Has the server check a Google Play purchase and record it for the signed-in account, then
     * starts uploading. Throws when the server couldn't record it; the purchase then mustn't be
     * acknowledged, so it's checked again later (or refunded by Google).
     */
    suspend fun verifyPurchase(purchaseToken: String) {
        if (prefs.userId == null) throw SyncException("Sign in to start cloud backup.")
        authorized { api.verifyPurchase(purchaseToken, it) }
        refreshAccess()
        requestSync()
    }

    /**
     * Reads the account's subscription again (after a purchase, or when the app returns), and for a
     * shared ledger, the account's access to it. Returns whether this device may upload.
     */
    suspend fun refreshAccess(): Boolean {
        if (prefs.userId == null || !config.isAvailable) return false
        try {
            storeAccess(authorized { api.cloudAccess(it) })
            val slot = prefs.ledgerSlot
            if (slot != null) {
                val access = authorized { api.ledgerAccess(slot, it) }
                prefs.ledgerAccess = access
                _status.update { it.copy(ledgerAccess = access) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or a passing failure: keep the last known answer; the server enforces it anyway.
            Log.i(TAG, "Couldn't check cloud access", e)
        }
        return canUpload
    }

    /** Lists the account's ledgers again; the last list stays when the server can't be reached. */
    suspend fun refreshLedgers(): List<LedgerInfo> {
        if (prefs.userId == null || !config.isAvailable) return emptyList()
        val ledgers = authorized { api.listLedgers(it) }
        prefs.ledgers = ledgers
        _status.update { it.copy(ledgers = ledgers) }
        return ledgers
    }

    suspend fun members(ledgerId: String): List<LedgerMember> = authorized { api.listMembers(ledgerId, it) }

    /** An editor invite to [ledgerId], for its owner to send. */
    suspend fun createInvite(ledgerId: String, role: String = "editor"): Invite = authorized { api.createInvite(ledgerId, role, it) }

    suspend fun inviteDetails(code: String): InviteDetails = authorized { api.inviteDetails(code.trim(), it) }

    /** Joins the ledger [code] invites to and returns its id. */
    suspend fun acceptInvite(code: String): String = authorized { api.acceptInvite(code.trim(), it) }.also { runCatching { refreshLedgers() } }

    suspend fun removeMember(ledgerId: String, userId: String) {
        authorized { api.removeMember(ledgerId, userId, it) }
        runCatching { refreshLedgers() }
    }

    suspend fun renameLedger(ledgerId: String, name: String) {
        authorized { api.renameLedger(ledgerId, name.trim(), it) }
        runCatching { refreshLedgers() }
    }

    /**
     * Deletes the account and its cloud copy for good. The ledger on this device stays and is no
     * longer linked to any account.
     */
    suspend fun deleteAccount() {
        authorized { api.deleteAccount(it) }
        stopForegroundWork()
        gate.withLock {
            prefs.clearSession()
            prefs.enabled = false
            prefs.dataOwnerId = null
            prefs.dataOwnerEmail = null
            prefs.cloudWipeBeforeMicros = null
        }
        publish()
    }

    private suspend fun <T> authorized(block: suspend (String) -> T): T = try {
        block(accessToken(forceRefresh = false))
    } catch (e: UnauthorizedException) {
        block(accessToken(forceRefresh = true))
    }

    private fun storeAccess(access: CloudAccess) {
        prefs.cloudAccess = access
        _status.update { it.copy(access = access) }
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

    /**
     * The device got a network connection: upload what was edited offline, and reconnect Realtime
     * now rather than when its backoff runs out (up to minutes after a long outage).
     */
    fun onNetworkAvailable() {
        requestSync()
        realtime.wake()
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

    /**
     * Asks the server whether sync may run, and shows why not. When the server can't be asked,
     * the last answer stands: offline, sync fails anyway, and otherwise it shouldn't depend on this.
     */
    private suspend fun serviceBlocked(): Boolean {
        val block = try {
            ServiceBlock.of(api.syncControl(), config.appVersionCode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return _status.value.serviceBlock != null
        }
        _status.update { it.copy(serviceBlock = block) }
        return block != null
    }

    /** Looks up the account's ledger on devices that signed in before ledgers existed. */
    private suspend fun ensureLedger() {
        if (prefs.ledgerId == null) prefs.ledgerId = authorized { api.ownLedger(it) }
    }

    /** Runs a pending [replaceCloudLedger]: deletes the old cloud rows and uploads this device's, before any pull. */
    private suspend fun applyPendingCloudWipe() {
        val before = prefs.cloudWipeBeforeMicros ?: return
        recordReplaced(engine.replaceCloudLedger(before, this).replacedLocalChanges)
        prefs.cloudWipeBeforeMicros = null
    }

    private fun fail(e: Exception) {
        Log.w(TAG, "Sync failed", e)
        _status.update {
            it.copy(lastError = SupabaseApi.userMessage(e), offline = e is OfflineException, needsSignIn = e is SessionExpiredException)
        }
        report(e)
    }

    /** Sends an unexpected failure to the project ([SyncErrorReport]), in the background and at most every 6 hours. */
    private fun report(e: Exception) {
        if (!SyncErrorReport.isReportable(e) || prefs.userId == null) return
        val report = SyncErrorReport.of(e, config.appVersionCode, _status.value.pendingChanges)
        val now = System.currentTimeMillis()
        synchronized(reported) {
            if (reported[report.fingerprint]?.let { now - it < REPORT_INTERVAL_MILLIS } == true) return
            reported[report.fingerprint] = now
        }
        scope.launch {
            try {
                authorized { api.reportSyncError(report, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(TAG, "Couldn't report the sync failure", e)
            }
        }
    }

    private fun succeeded() = _status.update { it.copy(lastError = null, offline = false, needsSignIn = false) }

    private fun recordReplaced(count: Int) {
        if (count == 0) return
        prefs.addReplacedChanges(count, System.currentTimeMillis())
        _status.update { it.copy(replacedChanges = prefs.replacedChanges, replacedAtMillis = prefs.replacedAtMillis) }
    }

    private fun setSyncing(value: Boolean) = _status.update { it.copy(isSyncing = value) }

    private fun publish() = _status.update {
        val base = snapshot().copy(isSyncing = it.isSyncing, pendingChanges = it.pendingChanges, serviceBlock = it.serviceBlock)
        if (prefs.userId == null) base else base.copy(lastError = it.lastError, offline = it.offline, needsSignIn = it.needsSignIn, ledgerFull = it.ledgerFull)
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
        access = prefs.userId?.let { prefs.cloudAccess },
        signedInWithGoogle = prefs.userId != null && prefs.signedInWithGoogle,
        sharedLedger = prefs.ledgerSlot != null,
        ledgerAccess = prefs.ledgerAccess,
        ledgers = prefs.userId?.let { prefs.ledgers }.orEmpty(),
        ledgerId = prefs.userId?.let { prefs.ledgerId },
    )

    private companion object {
        const val TAG = "SyncManager"
        const val REPORT_INTERVAL_MILLIS = 6 * 3600_000L
    }
}

/** How long one sync spent waiting and on each step, logged so a slow sync can be traced. */
private class SyncTiming(requestedAt: Long) {
    private val start = System.currentTimeMillis()
    private var last = start
    private val steps = mutableListOf<String>().apply { if (requestedAt > 0) add("waited ${start - requestedAt} ms") }

    fun step(name: String) {
        val now = System.currentTimeMillis()
        steps += "$name ${now - last} ms"
        last = now
    }

    override fun toString() = "Sync took ${System.currentTimeMillis() - start} ms (${steps.joinToString()})"
}
