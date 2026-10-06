package com.soloit.doubleentry.sync

import com.soloit.doubleentry.data.local.ServerClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncStatusTest {
    private val signedIn = SyncStatus(featureEnabled = true, configured = true, signedInEmail = "me@example.com", enabled = true)

    @Test
    fun nothingNeedsAttentionWhenSyncIsFine() {
        assertNull(signedIn.attention)
        assertNull(signedIn.copy(pendingChanges = 3).attention)
        // A failed sync with nothing waiting to upload puts no data at risk.
        assertNull(signedIn.copy(lastError = "Offline", offline = true).attention)
        assertNull(signedIn.copy(signedInEmail = null, needsSignIn = true, pendingChanges = 3).attention)
    }

    @Test
    fun attentionIsRankedByRisk() {
        val failing = signedIn.copy(lastError = "Server error", pendingChanges = 2, replacedChanges = 1)
        assertEquals(SyncAttention.Failing, failing.attention)
        assertEquals(SyncAttention.Offline, failing.copy(offline = true).attention)
        assertEquals(SyncAttention.SignInAgain, failing.copy(needsSignIn = true).attention)
        assertEquals(SyncAttention.Paused, signedIn.copy(enabled = false, pendingChanges = 1, replacedChanges = 1).attention)
        assertEquals(SyncAttention.Replaced, signedIn.copy(replacedChanges = 1).attention)
        // An expired session matters even when nothing is waiting yet.
        assertEquals(SyncAttention.SignInAgain, signedIn.copy(needsSignIn = true).attention)
    }

    @Test
    fun clockOffsetIsMeasuredFromTheDateHeader() {
        val sent = 1_000_000_000L
        val received = sent + 200
        // Device clock two minutes behind: the server's Date reads two minutes later than the midpoint.
        val serverDate = (sent + 100 + 120_000) / 1_000 * 1_000
        val offset = ServerClock.estimate(serverDate, sent, received, currentMicros = 0)!!
        assertEquals(120_000_000.0, offset.toDouble(), 1_000_000.0)
    }

    @Test
    fun smallOrUnreliableOffsetsAreIgnored() {
        val sent = 1_000_000_000L
        // Within the Date header's resolution: no correction.
        assertNull(ServerClock.estimate(sent, sent, sent + 100, currentMicros = 0))
        // A slow round trip says little about when the server answered.
        assertNull(ServerClock.estimate(sent + 60_000, sent, sent + 10_000, currentMicros = 0))
        // Responses built without a network call carry no send time.
        assertNull(ServerClock.estimate(sent + 60_000, 0, 0, currentMicros = 0))
        // A known offset isn't nudged by noise, but is cleared once the device clock is fixed.
        assertNull(ServerClock.estimate(sent + 60_000, sent, sent + 100, currentMicros = 60_000_000))
        assertEquals(0L, ServerClock.estimate(sent, sent, sent + 100, currentMicros = 60_000_000))
    }
}
