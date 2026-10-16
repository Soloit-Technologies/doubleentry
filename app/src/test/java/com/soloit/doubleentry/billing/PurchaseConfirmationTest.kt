package com.soloit.doubleentry.billing

import com.android.billingclient.api.Purchase
import com.soloit.doubleentry.sync.PurchaseOwnedElsewhereException
import com.soloit.doubleentry.sync.SyncException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A purchase is acknowledged with Google only after the server recorded it. */
@RunWith(RobolectricTestRunner::class)
class PurchaseConfirmationTest {
    private val steps = mutableListOf<String>()
    private val state = MutableStateFlow(BillingState())
    private var serverFailure: Exception? = null

    private val confirmation = PurchaseConfirmation(
        verify = { token -> steps += "verify $token"; serverFailure?.let { throw it } },
        acknowledge = { token -> steps += "acknowledge $token"; true },
        state = state,
    )

    /** A purchase as Google Play reports it (purchaseState 4 is pending). */
    private fun purchase(token: String, product: String = CloudBilling.PRODUCT_ID, acknowledged: Boolean = false, pending: Boolean = false) =
        Purchase(
            """{"orderId":"GPA.1","packageName":"com.soloit.doubleentry","productIds":["$product"],"purchaseTime":1760000000000,""" +
                """"purchaseState":${if (pending) 4 else 1},"purchaseToken":"$token","acknowledged":$acknowledged,"autoRenewing":true}""",
            "signature",
        )

    @Test
    fun theServerRecordsThePurchaseBeforeGoogleIsTold() = runBlocking<Unit> {
        confirmation.handle(purchase("t1"))
        assertEquals(listOf("verify t1", "acknowledge t1"), steps)
        assertFalse(state.value.confirming)
    }

    @Test
    fun aPurchaseTheServerCouldNotRecordIsNotAcknowledged() = runBlocking<Unit> {
        serverFailure = SyncException("We couldn't confirm your purchase yet. It will be checked again the next time the app opens.")
        confirmation.handle(purchase("t1"))
        assertEquals(listOf("verify t1"), steps)
        assertEquals("We couldn't confirm your purchase yet. It will be checked again the next time the app opens.", state.value.message)
        assertFalse(state.value.confirming)
    }

    @Test
    fun aPurchaseOfAnotherAccountIsNotAcknowledged() = runBlocking<Unit> {
        serverFailure = PurchaseOwnedElsewhereException()
        confirmation.handle(purchase("t1"))
        assertEquals(listOf("verify t1"), steps)
        assertTrue(state.value.message!!.contains("another DoubleEntry account"))
    }

    @Test
    fun anAcknowledgedPurchaseIsOnlyCheckedAgain() = runBlocking<Unit> {
        // Restoring on a new phone: the server records it for this account; Google already knows.
        confirmation.handle(purchase("t1", acknowledged = true))
        assertEquals(listOf("verify t1"), steps)
    }

    @Test
    fun aPendingPaymentWaitsWithoutAnything() = runBlocking<Unit> {
        confirmation.handle(purchase("t1", pending = true))
        assertEquals(emptyList<String>(), steps)
        assertTrue(state.value.pending)
    }

    @Test
    fun otherProductsAreLeftAlone() = runBlocking<Unit> {
        confirmation.handle(purchase("t1", product = "something_else"))
        assertEquals(emptyList<String>(), steps)
    }
}
