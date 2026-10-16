package com.soloit.doubleentry.sync

import com.soloit.doubleentry.data.SettingsRepository
import com.soloit.doubleentry.data.local.nowMicros
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.testing.FakePostgrest
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset

/** Sign-in decisions, whole-ledger replacement and resets, through SyncManager and a fake Supabase. */
@RunWith(RobolectricTestRunner::class)
class SyncManagerTest {
    private val server = FakePostgrest()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val devices = mutableListOf<Device>()
    private val day = LocalDate.of(2026, 9, 15)

    private class MemoryTokens : TokenStore {
        private val values = mutableMapOf<String, String>()
        override fun get(name: String) = values[name]
        override fun put(name: String, value: String?) {
            if (value == null) values.remove(name) else values[name] = value
        }
        override fun clear() = values.clear()
    }

    private inner class Device(name: String) {
        val t = TestLedger(name)
        val prefs = SyncPreferences(t.context, MemoryTokens())
        private val config = SupabaseConfig("https://project.supabase.co", "anon", true)
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
}
