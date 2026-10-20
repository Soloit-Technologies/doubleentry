package com.soloit.doubleentry.ui

import kotlinx.serialization.Serializable

/** [accountId] and [date] (ISO) prefill a new transaction, e.g. one started from an account's ledger. */
@Serializable
data class TransactionEditorRoute(
    val editId: String? = null,
    val duplicateId: String? = null,
    val accountId: String? = null,
    val date: String? = null,
)
