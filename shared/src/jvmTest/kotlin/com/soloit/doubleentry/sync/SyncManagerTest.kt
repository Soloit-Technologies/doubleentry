package com.soloit.doubleentry.sync

import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.testing.FakePostgrest
import com.soloit.doubleentry.testing.MemoryTokenStore
import com.soloit.doubleentry.testing.TestLedger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Sign-in decisions, whole-ledger replacement and resets, through SyncManager and a fake Supabase. */
class SyncManagerTest {
    private val server = FakePostgrest()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val devices = mutableListOf<Device>()
    private val day = LocalDate.of(2026, 9, 15)

    private inner class Device(name: String, version: Int = 13) {
        val t = TestLedger(name)
        val prefs = SyncPreferences(t.stores, MemoryTokenStore())
        private val config = SupabaseConfig("https://project.supabase.co", "anon", true, appVersionCode = version)
        private val api = SupabaseApi(config, server.client)
        val sync = SyncManager(config, prefs, api, SyncEngine(t.db, api, prefs), scope, t.db.syncStatus().pendingChanges())

        init {
            devices += this
        }

        suspend fun ledgerWithOneTransaction(title: String): String {
            val bank = t.account(1910, "Bank", AccountType.Asset)
            val food = t.account(5010, "Food", AccountType.Expense)
            return t.post(day, title, food, bank, 100_00)
        }

        suspend fun titles(): Set<String> = t.ledger.getTransactions().map { it.title }.toSet()
    }

    @After
    fun tearDown() {
        scope.cancel()
        devices.forEach { it.t.close() }
    }

    private fun cloudTitles(ledger: String = server.ledgerIdFor("me@example.com")): Set<String> =
        server.liveRows("transactions", ledger).map { it.getValue("title").jsonPrimitive.content }.toSet()

    @Test
    fun aFreshDeviceDownloadsWithoutAsking() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("From A")
        assertNull(a.sync.signIn("me@example.com", "pw"))
        val b = Device("b")
        assertNull(b.sync.signIn("me@example.com", "pw"))
        assertEquals(setOf("From A"), b.titles())
    }

    @Test
    fun aNeverSyncedLedgerMovesToAnEmptyAccountWithoutAsking() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Mine")
        assertNull(a.sync.signIn("me@example.com", "pw"))
        assertEquals(setOf("Mine"), cloudTitles())
        assertEquals(2, server.liveRows("accounts", server.ledgerIdFor("me@example.com")).size)
    }

    @Test
    fun switchingAccountsAsksAndKeepsBothLedgersApart() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Old account")
        a.sync.signIn("old@example.com", "pw")
        a.sync.signOut()

        val question = a.sync.signIn("me@example.com", "pw") as SignInQuestion.OtherAccount
        assertEquals("old@example.com", question.previousEmail)
        a.sync.completeSignIn(SignInChoice.UseCloud)

        assertEquals(emptySet<String>(), a.titles())
        assertEquals(emptySet<String>(), cloudTitles())
        assertEquals(setOf("Old account"), cloudTitles(server.ledgerIdFor("old@example.com")))
    }

    @Test
    fun copyingALedgerToAnotherAccountKeepsBothCopies() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Shared history")
        a.sync.signIn("old@example.com", "pw")
        a.sync.signOut()

        val question = a.sync.signIn("me@example.com", "pw") as SignInQuestion.OtherAccount
        assertTrue(!question.accountHasData)
        a.sync.completeSignIn(SignInChoice.UseThisDevice)
        assertNull(a.sync.status.value.lastError)

        // The same row ids now exist in both accounts, independently.
        assertEquals(setOf("Shared history"), cloudTitles())
        assertEquals(setOf("Shared history"), cloudTitles(server.ledgerIdFor("old@example.com")))
        val id = a.t.ledger.getTransactions().single().id
        a.t.ledger.deleteTransaction(id)
        a.sync.syncNow()
        assertEquals(emptySet<String>(), cloudTitles())
        assertEquals(setOf("Shared history"), cloudTitles(server.ledgerIdFor("old@example.com")))
    }

    @Test
    fun twoUsersWithTheDefaultPlanShareOneProject() = runBlocking<Unit> {
        val a = Device("a")
        val b = Device("b")
        val planSize = a.t.ledger.seedDefaultPlan()
        b.t.ledger.seedDefaultPlan()
        // Default-plan ids are the same for everyone; per-ledger keys keep them apart.
        assertNull(a.sync.signIn("a@example.com", "pw"))
        assertNull(b.sync.signIn("b@example.com", "pw"))
        assertNull(a.sync.status.value.lastError)
        assertNull(b.sync.status.value.lastError)
        assertEquals(planSize, server.liveRows("accounts", server.ledgerIdFor("a@example.com")).size)
        assertEquals(planSize, server.liveRows("accounts", server.ledgerIdFor("b@example.com")).size)

        val bank = a.t.ledger.getAccounts().first()
        a.t.ledger.updateAccount(bank.id, bank.number, "Renamed by A", bank.type)
        a.sync.syncNow()
        b.sync.syncNow()
        assertEquals(bank.name, b.t.ledger.getAccounts().single { it.id == bank.id }.name)
    }

    @Test
    fun twoLedgersAskAndUseCloudReplacesThisDevice() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Cloud ledger")
        a.sync.signIn("me@example.com", "pw")
        val b = Device("b")
        b.ledgerWithOneTransaction("Device ledger")

        assertTrue(b.sync.signIn("me@example.com", "pw") is SignInQuestion.TwoLedgers)
        b.sync.completeSignIn(SignInChoice.UseCloud)

        assertEquals(setOf("Cloud ledger"), b.titles())
        assertEquals(setOf("Cloud ledger"), cloudTitles())
        assertEquals(2, b.t.ledger.getAccounts().size)
    }

    @Test
    fun twoLedgersAskAndUseThisDeviceReplacesTheCloud() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Cloud ledger")
        a.sync.signIn("me@example.com", "pw")
        val b = Device("b")
        b.ledgerWithOneTransaction("Device ledger")

        assertTrue(b.sync.signIn("me@example.com", "pw") is SignInQuestion.TwoLedgers)
        b.sync.completeSignIn(SignInChoice.UseThisDevice)

        assertEquals(setOf("Device ledger"), cloudTitles())
        assertEquals(2, server.liveRows("accounts", server.ledgerIdFor("me@example.com")).size)
        a.sync.syncNow()
        assertEquals(setOf("Device ledger"), a.titles())
        assertEquals(2, a.t.ledger.getAccounts().size)
    }

    @Test
    fun cancellingTwoLedgersSignsNothingIn() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Cloud ledger")
        a.sync.signIn("me@example.com", "pw")
        val b = Device("b")
        b.ledgerWithOneTransaction("Device ledger")

        assertTrue(b.sync.signIn("me@example.com", "pw") is SignInQuestion.TwoLedgers)
        b.sync.cancelSignIn()
        assertTrue(!b.sync.status.value.isSignedIn)
        assertEquals(setOf("Device ledger"), b.titles())
        assertEquals(setOf("Cloud ledger"), cloudTitles())
    }

    @Test
    fun deletingEverythingWhileSignedOutAsksBeforeTouchingTheCloud() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Keep me")
        a.sync.signIn("me@example.com", "pw")
        a.sync.signOut()
        val before = nowMicros()
        a.t.ledger.deleteAllData()
        a.sync.replaceCloudLedger(before)
        assertEquals(setOf("Keep me"), cloudTitles())

        val question = a.sync.signIn("me@example.com", "pw") as SignInQuestion.ChangesWhileSignedOut
        assertEquals(3, question.deletions)
        a.sync.completeSignIn(SignInChoice.UseCloud)
        assertEquals(setOf("Keep me"), a.titles())
        assertEquals(setOf("Keep me"), cloudTitles())
    }

    @Test
    fun uploadingADeleteAllMadeWhileSignedOutClearsTheCloud() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Old")
        a.sync.signIn("me@example.com", "pw")
        a.sync.signOut()
        val before = nowMicros()
        a.t.ledger.deleteAllData()
        a.sync.replaceCloudLedger(before)

        assertTrue(a.sync.signIn("me@example.com", "pw") is SignInQuestion.ChangesWhileSignedOut)
        a.sync.completeSignIn(SignInChoice.UseThisDevice)
        assertEquals(emptySet<String>(), cloudTitles())
        assertEquals(0, server.liveRows("accounts", server.ledgerIdFor("me@example.com")).size)
    }

    @Test
    fun deleteAllAlsoRemovesCloudRowsThisDeviceNeverPulled() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Seen")
        a.sync.signIn("me@example.com", "pw")
        val b = Device("b")
        b.sync.signIn("me@example.com", "pw")
        // B adds a transaction that A hasn't pulled when it deletes everything.
        val bank = b.t.ledger.getAccounts().single { it.number == 1910 }
        val food = b.t.ledger.getAccounts().single { it.number == 5010 }
        b.t.post(day, "Unseen", food, bank, 5_00)
        b.sync.syncNow()

        val before = nowMicros()
        a.t.ledger.deleteAllData()
        a.sync.replaceCloudLedger(before)
        a.sync.syncNow()

        assertEquals(emptySet<String>(), cloudTitles())
        b.sync.syncNow()
        assertEquals(emptySet<String>(), b.titles())
    }

    @Test
    fun deleteAllKeepsReportSettingsEverywhere() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Gone")
        a.t.settings.put(SettingsRepository.Keys.TAX_PREFIXES, "89")
        a.sync.signIn("me@example.com", "pw")
        val b = Device("b")
        b.sync.signIn("me@example.com", "pw")

        // Delete all removes the ledger but not report settings, here or in the cloud.
        val before = nowMicros()
        a.t.ledger.deleteAllData()
        a.sync.replaceCloudLedger(before)
        a.sync.syncNow()
        b.sync.syncNow()

        assertEquals(emptySet<String>(), b.titles())
        assertEquals("89", a.t.settings.get(SettingsRepository.Keys.TAX_PREFIXES))
        assertEquals("89", b.t.settings.get(SettingsRepository.Keys.TAX_PREFIXES))
    }

    @Test
    fun resetAsksWhenChangesCouldNotBeUploaded() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Synced")
        a.sync.signIn("me@example.com", "pw")
        assertEquals(0, a.sync.uploadBeforeReset())

        a.sync.setEnabled(false)
        val bank = a.t.ledger.getAccounts().single { it.number == 1910 }
        val food = a.t.ledger.getAccounts().single { it.number == 5010 }
        a.t.post(day, "Only here", food, bank, 1_00)
        assertEquals(1, a.sync.uploadBeforeReset())

        a.sync.resetDevice()
        assertEquals(emptySet<String>(), a.titles())
        assertEquals(setOf("Synced"), cloudTitles())
        // The wiped device has no owner any more: signing in downloads without asking.
        assertNull(a.sync.signIn("me@example.com", "pw"))
        assertEquals(setOf("Synced"), a.titles())
    }

    @Test
    fun aDeviceSignedInBeforeLedgersFindsItsLedgerOnTheNextSync() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Before")
        a.sync.signIn("me@example.com", "pw")
        // An install from before ledgers: signed in, without a ledger.
        a.prefs.ledgerId = null
        val accounts = a.t.ledger.getAccounts()
        a.t.post(day, "After", accounts.single { it.number == 5010 }, accounts.single { it.number == 1910 }, 1_00)

        assertTrue(a.sync.syncNow())
        assertEquals(server.ledgerIdFor("me@example.com"), a.prefs.ledgerId)
        assertEquals(setOf("Before", "After"), cloudTitles())
    }

    @Test
    fun aRowCommittedLateWithAnOldStampIsStillPulled() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("First")
        a.sync.signIn("me@example.com", "pw")
        val b = Device("b")
        b.sync.signIn("me@example.com", "pw")

        // A slow upload stamped a minute ago commits only now, behind B's watermark.
        val stamp = java.time.Instant.now().minusSeconds(60).atOffset(ZoneOffset.UTC).toString()
        server.insertRaw(
            "transactions",
            JsonObject(
                mapOf(
                    "id" to JsonPrimitive("00000000-0000-4000-8000-000000000001"),
                    "ledger_id" to JsonPrimitive(server.ledgerIdFor("me@example.com")),
                    "date" to JsonPrimitive(day.toString()),
                    "title" to JsonPrimitive("Late"),
                    "description" to JsonPrimitive(""),
                    "lines" to JsonArray(emptyList()),
                    "modified_at" to JsonPrimitive(stamp),
                    "updated_at" to JsonPrimitive(stamp),
                ),
            ),
        )
        b.sync.syncNow()
        assertEquals("Late", b.t.db.transactions().byId("00000000-0000-4000-8000-000000000001")?.title)
    }

    @Test
    fun withoutASubscriptionChangesStayLocalButTheCloudCopyStillRestores() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Backed up")
        a.sync.signIn("me@example.com", "pw")
        server.withoutAccess += server.userIdFor("me@example.com")

        // A new phone gets everything back, even after the subscription ended.
        val b = Device("b")
        assertNull(b.sync.signIn("me@example.com", "pw"))
        assertEquals(setOf("Backed up"), b.titles())
        assertEquals(false, b.sync.status.value.access?.active)
        assertTrue(b.sync.status.value.notBackedUp)

        // New changes stay on the device, without an error.
        val accounts = b.t.ledger.getAccounts()
        b.t.post(day, "Not yet", accounts.first { it.number == 5010 }, accounts.first { it.number == 1910 }, 50_00)
        b.sync.syncNow()
        assertNull(b.sync.status.value.lastError)
        assertEquals(setOf("Backed up"), cloudTitles())

        // Subscribing uploads them.
        server.withoutAccess.clear()
        b.sync.syncNow()
        assertEquals(true, b.sync.status.value.access?.active)
        assertEquals(setOf("Backed up", "Not yet"), cloudTitles())
    }

    @Test
    fun aLedgerWaitingToReplaceTheCloudIsNotMergedWithItWithoutASubscription() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Cloud ledger")
        a.sync.signIn("me@example.com", "pw")
        server.withoutAccess += server.userIdFor("me@example.com")
        val b = Device("b")
        b.ledgerWithOneTransaction("Device ledger")

        assertTrue(b.sync.signIn("me@example.com", "pw") is SignInQuestion.TwoLedgers)
        b.sync.completeSignIn(SignInChoice.UseThisDevice)
        assertEquals(setOf("Device ledger"), b.titles())
        assertEquals(setOf("Cloud ledger"), cloudTitles())

        server.withoutAccess.clear()
        b.sync.syncNow()
        assertEquals(setOf("Device ledger"), b.titles())
        assertEquals(setOf("Device ledger"), cloudTitles())
    }

    @Test
    fun signingInWithGoogleReachesTheSameLedgerAsEmail() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("From email")
        a.sync.signIn("me@example.com", "pw")

        // Supabase links a Google identity with the same verified email to the existing account.
        val b = Device("b")
        assertNull(b.sync.signInWithGoogle("me@example.com", "nonce", "me@example.com"))
        assertEquals(setOf("From email"), b.titles())
        assertTrue(b.sync.status.value.signedInWithGoogle)
        assertTrue(!a.sync.status.value.signedInWithGoogle)

        b.sync.signOut()
        assertTrue(!b.sync.status.value.signedInWithGoogle)
    }

    @Test
    fun browserSignInWithGoogleReachesTheSameAccount() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("From the phone")
        assertNull(a.sync.signInWithGoogle("me@example.com", "nonce", "me@example.com"))

        // The desktop app signs in through the browser and gets a code for the same Google account.
        val b = Device("b")
        val verifier = Pkce.verifier()
        val url = b.sync.googleSignInUrl("http://127.0.0.1:5000/auth/callback", Pkce.challenge(verifier))
        assertTrue(url.startsWith("https://project.supabase.co/auth/v1/authorize?provider=google&"))
        assertNull(b.sync.signInWithGoogleCode("me@example.com", verifier))
        assertEquals(setOf("From the phone"), b.titles())
        assertEquals("me@example.com", b.sync.status.value.signedInEmail)
        assertTrue(b.sync.status.value.signedInWithGoogle)
    }

    @Test
    fun pausingSyncOnTheServerStopsItEverywhereAndKeepsTheChanges() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Synced")
        a.sync.signIn("me@example.com", "pw")
        server.syncPaused = true
        val accounts = a.t.ledger.getAccounts()
        a.t.post(day, "Waiting", accounts.single { it.number == 5010 }, accounts.single { it.number == 1910 }, 1_00)

        val requestsBefore = server.requests.size
        a.sync.syncNow()
        assertEquals(listOf("POST /rest/v1/rpc/sync_control"), server.requests.drop(requestsBefore))
        assertEquals(SyncAttention.ServicePaused, a.sync.status.value.attention)
        assertEquals(setOf("Synced"), cloudTitles())

        server.syncPaused = false
        assertTrue(a.sync.syncNow())
        assertNull(a.sync.status.value.attention)
        assertEquals(setOf("Synced", "Waiting"), cloudTitles())
    }

    @Test
    fun anOutdatedBuildStopsSyncingAndAsksForTheUpdate() = runBlocking<Unit> {
        val old = Device("old", version = 13)
        old.ledgerWithOneTransaction("Synced")
        old.sync.signIn("me@example.com", "pw")
        server.minVersionCode = 14
        server.serviceMessage = "This version can damage synced data. Update to keep syncing."

        old.sync.syncNow()
        val block = old.sync.status.value.serviceBlock!!
        assertTrue(block.updateRequired)
        assertEquals("This version can damage synced data. Update to keep syncing.", block.message)
        assertEquals(SyncAttention.UpdateRequired, old.sync.status.value.attention)

        val updated = Device("updated", version = 14)
        assertNull(updated.sync.signIn("me@example.com", "pw"))
        assertEquals(setOf("Synced"), updated.titles())
        assertNull(updated.sync.status.value.serviceBlock)
    }

    @Test
    fun signingInWhileSyncIsStoppedChangesNothing() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Cloud")
        a.sync.signIn("me@example.com", "pw")
        server.syncPaused = true

        // This device would be wiped to download the cloud's ledger: refused before anything happens.
        val b = Device("b")
        b.ledgerWithOneTransaction("Device")
        val refused = runCatching { b.sync.signIn("me@example.com", "pw") }.exceptionOrNull()
        assertTrue(refused is SyncException)
        assertNull(b.prefs.userId)
        assertEquals(setOf("Device"), b.titles())
    }

    @Test
    fun syncRunsWhenTheSwitchCantBeRead() = runBlocking<Unit> {
        val a = Device("a")
        a.ledgerWithOneTransaction("Synced")
        a.sync.signIn("me@example.com", "pw")
        server.fail(FakePostgrest.Failure.Unavailable, times = Int.MAX_VALUE) { it.url.encodedPath.endsWith("/sync_control") }
        val accounts = a.t.ledger.getAccounts()
        a.t.post(day, "Still syncs", accounts.single { it.number == 5010 }, accounts.single { it.number == 1910 }, 1_00)

        assertTrue(a.sync.syncNow())
        assertEquals(setOf("Synced", "Still syncs"), cloudTitles())
    }
}
