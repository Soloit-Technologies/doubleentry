package com.soloit.doubleentry.ui.settings

import com.soloit.doubleentry.sync.SignInChoice
import com.soloit.doubleentry.sync.SignInQuestion
import com.soloit.doubleentry.sync.SyncAttention
import com.soloit.doubleentry.sync.SyncStatus

fun changesText(count: Int): String = if (count == 1) "1 change" else "$count changes"

/** One line on what needs the user's attention, or null when sync is fine. */
fun SyncStatus.attentionText(): String? = when (attention) {
    SyncAttention.UpdateRequired -> "Update the app to keep syncing" +
        if (pendingChanges > 0) " · ${changesText(pendingChanges)} only on this device" else ""
    SyncAttention.ServicePaused -> "Cloud sync is paused for maintenance" +
        if (pendingChanges > 0) " · ${changesText(pendingChanges)} only on this device" else ""
    SyncAttention.SignInAgain -> "Sign in again to keep syncing" +
        if (pendingChanges > 0) " · ${changesText(pendingChanges)} only on this device" else ""
    SyncAttention.LostAccess -> "You no longer have access to this shared ledger"
    SyncAttention.NotBackedUp -> (if (sharedLedger) "The owner's cloud backup isn't active" else "Cloud backup isn't active") +
        " · ${changesText(pendingChanges)} only on this device"
    SyncAttention.Failing -> "Sync isn't working · ${changesText(pendingChanges)} not uploaded yet"
    SyncAttention.Offline -> "Offline · ${changesText(pendingChanges)} will upload when you're back online"
    SyncAttention.Paused -> "Sync is paused · ${changesText(pendingChanges)} only on this device"
    SyncAttention.Replaced -> "${changesText(replacedChanges)} from this device ${if (replacedChanges == 1) "was" else "were"} " +
        "replaced by newer edits from another device"
    null -> null
}

/** How a [SignInQuestion] is put to the user: [keepDevice] answers [SignInChoice.UseThisDevice], [useCloud] [SignInChoice.UseCloud]. */
data class SignInPrompt(val title: String, val message: String, val keepDevice: String, val useCloud: String)

fun SignInQuestion.prompt(): SignInPrompt =
    when (this) {
        is SignInQuestion.ChangesWhileSignedOut -> {
            val title = "Changes made while signed out"
            val message = "This device has ${changesText(changes)} that ${if (changes == 1) "was" else "were"} made while you were signed out" +
                (if (deletions > 0) ", including $deletions deleted item${if (deletions == 1) "" else "s"}" else "") +
                ". Upload them to $email, or discard them and use the data in the cloud?" +
                (if (deletions > 0) "\n\nUploading also deletes those items on your other devices." else "")
            val keepDevice = "Upload my changes"
            val useCloud = "Discard them, use cloud data"
            SignInPrompt(title, message, keepDevice, useCloud)
        }
        is SignInQuestion.TwoLedgers -> {
            val title = "Which data do you want to keep?"
            val message = "This device has its own ledger, and $email already has one in the cloud. " +
                "Only one can be kept; combining them would duplicate accounts and transactions.\n\n" +
                "• Use cloud data: the ledger on this device is deleted and replaced with the one from the cloud.\n" +
                "• Use this device's data: the ledger in the cloud is replaced, on all your devices.\n\n" +
                "Not sure? Cancel and export a backup first (Settings → Data management)."
            val keepDevice = "Use this device's data"
            val useCloud = "Use cloud data"
            SignInPrompt(title, message, keepDevice, useCloud)
        }
        is SignInQuestion.OtherAccount -> {
            val previous = previousEmail ?: "another account"
            val title = "Switch to $email?"
            val message = "The ledger on this device belongs to $previous, which keeps its copy in the cloud either way.\n\n" +
                (if (accountHasData) {
                    "• Use $email's ledger: this device switches to the ledger already in that account.\n" +
                        "• Copy this ledger: it replaces the ledger in $email, on all devices signed in to it."
                } else {
                    "• Start empty: $email starts with a new, empty ledger.\n" +
                        "• Copy this ledger: $email gets a copy of it."
                }) +
                if (unsyncedChanges > 0) {
                    "\n\n${changesText(unsyncedChanges)} on this device never reached $previous and will only be kept if you copy."
                } else {
                    ""
                }
            val keepDevice = "Copy this ledger"
            val useCloud = if (accountHasData) "Use $email's ledger" else "Start empty"
            SignInPrompt(title, message, keepDevice, useCloud)
        }
    }
