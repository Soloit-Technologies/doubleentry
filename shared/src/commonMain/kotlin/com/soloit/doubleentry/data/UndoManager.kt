package com.soloit.doubleentry.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** A just-completed delete the user can take back from a snackbar. */
data class UndoEvent(val message: String, val undo: suspend () -> Unit)

/**
 * App-wide channel for "Deleted · Undo" snackbars. Screens that delete something usually close
 * right away, so the snackbar is shown by the root scaffold and the undo runs in the app scope.
 */
class UndoManager(private val scope: CoroutineScope) {
    private val _events = MutableSharedFlow<UndoEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UndoEvent> = _events.asSharedFlow()

    fun offer(message: String, undo: suspend () -> Unit) {
        _events.tryEmit(UndoEvent(message, undo))
    }

    /** Offers to restore everything deleted with [stamp] (see [LedgerRepository.restoreDeleted]). */
    fun offerRestore(message: String, ledger: LedgerRepository, stamp: Long) = offer(message) { ledger.restoreDeleted(stamp) }

    fun run(event: UndoEvent) {
        scope.launch { runCatching { event.undo() } }
    }
}
