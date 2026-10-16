package com.soloit.doubleentry.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.sync.Invite
import com.soloit.doubleentry.sync.InviteDetails
import com.soloit.doubleentry.sync.LedgerInfo
import com.soloit.doubleentry.sync.LedgerMember
import com.soloit.doubleentry.sync.SupabaseApi
import com.soloit.doubleentry.ui.LocalAppContainer
import com.soloit.doubleentry.ui.components.ConfirmDialog
import com.soloit.doubleentry.ui.components.GroupedItem
import com.soloit.doubleentry.ui.components.SectionCard
import com.soloit.doubleentry.ui.components.SectionHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

fun roleLabel(role: String?): String = when (role) {
    "owner" -> "Owner"
    "editor" -> "Can edit"
    "viewer" -> "Can view"
    else -> "No access"
}

/** What a ledger is to the signed-in account, in one line. */
private fun LedgerInfo.subtitle(): String = if (isOwner) {
    if (memberCount > 1) "Yours · shared with ${memberCount - 1} ${if (memberCount == 2) "person" else "people"}" else "Yours"
} else {
    "Shared by ${ownerEmail ?: "someone"} · ${roleLabel(role)}"
}

/** The account's ledgers: which one is open here, who's in it, inviting people and joining theirs. */
@Composable
fun LedgersScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val sync = container.sync
    val sessions = container.sessions
    val status by sync.status.collectAsStateWithLifecycle()
    val onDevice by sessions.sharedOnDevice.collectAsStateWithLifecycle()
    // App-wide scope: opening or joining a ledger finishes even if the user leaves this screen.
    val scope = container.appScope

    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var members by remember { mutableStateOf<List<LedgerMember>?>(null) }
    var membersVersion by remember { mutableIntStateOf(0) }
    var joining by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf<Invite?>(null) }
    var removing by remember { mutableStateOf<LedgerMember?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }

    val open = status.ledger
    val ledgerId = status.ledgerId

    fun run(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = SupabaseApi.userMessage(e)
            } finally {
                busy = null
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { sync.refreshLedgers() } }
    LaunchedEffect(ledgerId, membersVersion, status.ledgers) {
        members = ledgerId?.takeIf { !status.lostLedgerAccess }?.let { id -> runCatching { sync.members(id) }.getOrNull() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ledgers & sharing") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!status.isSignedIn) {
                    Text("Sign in to share a ledger or join one.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    return@Column
                }
                busy?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                if (status.lostLedgerAccess) {
                    LostAccessCard(onRemove = { ledgerId?.let { id -> run("Removing it from this device…") { sessions.removeFromDevice(id) } } })
                }

                // The account's ledgers; shared ones on this device that the server no longer lists
                // still show, so they can be removed.
                val listed = status.ledgers
                val unlisted = onDevice.filter { local -> listed.none { it.id == local.id } }
                SectionHeader("Your ledgers")
                Column {
                    val count = listed.size + unlisted.size
                    listed.forEachIndexed { i, ledger ->
                        val isOpen = ledger.id == ledgerId
                        GroupedItem(i, count, onClick = { if (!isOpen) run("Opening ${ledger.name}…") { sessions.open(ledger.id) } }) {
                            SettingRow(
                                if (ledger.isOwner) Icons.AutoMirrored.Rounded.MenuBook else Icons.Rounded.Group,
                                ledger.name,
                                ledger.subtitle(),
                                trailing = if (isOpen) ({ Icon(Icons.Rounded.Check, "Open on this device", tint = MaterialTheme.colorScheme.primary) }) else null,
                            )
                        }
                    }
                    unlisted.forEachIndexed { i, ledger ->
                        GroupedItem(listed.size + i, count, onClick = { run("Removing ${ledger.name}…") { sessions.removeFromDevice(ledger.id) } }) {
                            SettingRow(Icons.Rounded.Group, ledger.name, "No longer shared with you · tap to remove it from this device")
                        }
                    }
                    if (count == 0) Text("Couldn't load your ledgers. Check the connection and try again.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { joining = true }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Rounded.Login, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Join a shared ledger")
                }

                if (open != null && !status.lostLedgerAccess) {
                    SectionHeader(open.name)
                    if (open.isOwner) {
                        Column {
                            GroupedItem(0, 2, onClick = {
                                run("Creating an invite…") { invite = sync.createInvite(open.id) }
                            }) {
                                SettingRow(Icons.Rounded.GroupAdd, "Invite someone", "They get a code to join this ledger and edit it with you", chevron = true)
                            }
                            GroupedItem(1, 2, onClick = { renaming = true }) {
                                SettingRow(Icons.Rounded.DriveFileRenameOutline, "Rename", open.name, chevron = true)
                            }
                        }
                    }
                    MembersList(
                        members = members,
                        me = sync.userId,
                        canRemove = open.isOwner,
                        onRemove = { removing = it },
                    )
                    if (!open.isOwner) {
                        TextButton(onClick = { confirmLeave = true }, enabled = busy == null, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Icon(Icons.AutoMirrored.Rounded.Logout, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Leave this ledger", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                HowSharingWorksCard()
            }
        }
    }

    if (joining) {
        JoinDialog(
            lookUp = { code -> sync.inviteDetails(code) },
            onJoin = { code, details ->
                joining = false
                run("Joining ${details.ledgerName}…") { sessions.join(code) }
            },
            onDismiss = { joining = false },
        )
    }

    if (renaming && open != null) {
        RenameDialog(
            current = open.name,
            onRename = { name ->
                renaming = false
                run("Renaming…") { sync.renameLedger(open.id, name) }
            },
            onDismiss = { renaming = false },
        )
    }

    invite?.let { created ->
        InviteDialog(
            invite = created,
            ledgerName = open?.name.orEmpty(),
            ownerBacksUp = status.access?.active == true,
            onDismiss = { invite = null },
        )
    }

    removing?.let { member ->
        ConfirmDialog(
            title = "Remove ${member.email ?: "this person"}?",
            message = "They can no longer see or change this ledger. What they already entered stays in it.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = {
                removing = null
                val id = ledgerId ?: return@ConfirmDialog
                run("Removing…") {
                    sync.removeMember(id, member.userId)
                    membersVersion++
                }
            },
            onDismiss = { removing = null },
        )
    }

    if (confirmLeave && open != null) {
        ConfirmDialog(
            title = "Leave ${open.name}?",
            message = "It's removed from this device and you can no longer see or change it. What you entered stays in it. " +
                "To come back, ask ${open.ownerEmail ?: "the owner"} for a new invite." +
                if (status.pendingChanges > 0) "\n\n${changesText(status.pendingChanges)} not uploaded yet will be lost." else "",
            confirmLabel = "Leave",
            destructive = true,
            onConfirm = {
                confirmLeave = false
                run("Leaving ${open.name}…") { sessions.leave(open.id) }
            },
            onDismiss = { confirmLeave = false },
        )
    }
}

@Composable
private fun MembersList(members: List<LedgerMember>?, me: String?, canRemove: Boolean, onRemove: (LedgerMember) -> Unit) {
    Column {
        when {
            members == null -> Text("Loading members…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> members.forEachIndexed { i, member ->
                val isMe = member.userId == me
                GroupedItem(i, members.size) {
                    SettingRow(
                        Icons.Rounded.Person,
                        (member.email ?: "Unknown") + if (isMe) " (you)" else "",
                        roleLabel(member.role),
                        trailing = if (canRemove && member.role != "owner") {
                            { IconButton(onClick = { onRemove(member) }) { Icon(Icons.Rounded.PersonRemove, "Remove ${member.email}") } }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun LostAccessCard(onRemove: () -> Unit) {
    SectionCard(contentPadding = PaddingValues(20.dp), containerColor = MaterialTheme.colorScheme.errorContainer) {
        Text("No longer shared with you", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        Spacer(Modifier.height(4.dp))
        Text(
            "The owner removed you from this ledger, or deleted it. Changes can't be uploaded to it any more. " +
                "Remove it from this device to go back to your own ledger.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text("Remove from this device") }
    }
}

/** Asks for a code, shows what it's an invite to, then joins. */
@Composable
private fun JoinDialog(lookUp: suspend (String) -> InviteDetails, onJoin: (String, InviteDetails) -> Unit, onDismiss: () -> Unit) {
    val scope = LocalAppContainer.current.appScope
    var code by remember { mutableStateOf("") }
    var details by remember { mutableStateOf<InviteDetails?>(null) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun check() {
        if (code.isBlank() || checking) return
        checking = true
        error = null
        scope.launch {
            try {
                details = lookUp(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = SupabaseApi.userMessage(e)
            } finally {
                checking = false
            }
        }
    }

    val found = details
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (found == null) "Join a shared ledger" else "Join ${found.ledgerName}?") },
        text = {
            Column {
                if (found == null) {
                    Text("Enter the code from the invite you received.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; error = null },
                        label = { Text("Invite code") },
                        placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { check() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (found.alreadyMember) {
                    Text("You're already in ${found.ledgerName}. Open it?")
                } else {
                    Text(
                        "${found.ownerEmail ?: "Someone"} invites you to ${found.ledgerName}, where you ${if (found.role == "viewer") "can see" else "can see and change"} everything.\n\n" +
                            "It opens on this device in place of your own ledger, which stays as it is. You can switch between them here.",
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (found == null) {
                TextButton(onClick = ::check, enabled = code.isNotBlank() && !checking) {
                    if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Continue")
                }
            } else {
                TextButton(onClick = { onJoin(code, found) }) { Text(if (found.alreadyMember) "Open" else "Join") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    val valid = name.trim().length in 1..100
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ledger") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onRename(name) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = valid && name.trim() != current) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shows a new invite code and sends it through the share sheet. */
@Composable
private fun InviteDialog(invite: Invite, ledgerName: String, ownerBacksUp: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val expires = invite.expiresAtMillis?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
    val message = "Join my ledger “$ledgerName” in Double Entry.\n\n" +
        "In the app, open Settings → Cloud backup & sync → Ledgers & sharing → Join a shared ledger, and enter this code:\n\n" +
        "${invite.code}\n\n" +
        "The code works once" + (expires?.let { " and expires on $it" } ?: "") + "."
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Invite someone") },
        text = {
            Column {
                Text("Send this code to the person you want to share $ledgerName with. Whoever enters it can see and change the whole ledger.")
                Spacer(Modifier.height(12.dp))
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        invite.code,
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(12.dp).align(Alignment.CenterHorizontally),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Works once" + (expires?.let { ", until $it" } ?: "") + ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!ownerBacksUp) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Your cloud backup isn't active, so nobody's changes, yours included, are uploaded to this ledger until it is.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, message)
                context.startActivity(Intent.createChooser(send, "Send invite"))
            }) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun HowSharingWorksCard() {
    SectionCard(contentPadding = PaddingValues(20.dp)) {
        Text("How sharing works", style = MaterialTheme.typography.titleSmall)
        listOf(
            "Everyone in a ledger sees the same accounts, transactions, invoices, assets and report settings, and changes show up for the others as they sync.",
            "Your cloud backup covers the people you invite: their changes upload to your ledger while it's active.",
            "A shared ledger opens in place of your own. Your own ledger stays as it is; switch between them above.",
            "Signing out removes shared ledgers from this device. They come back when you sign in and open them again.",
        ).forEach { line ->
            Text(
                "•  $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
