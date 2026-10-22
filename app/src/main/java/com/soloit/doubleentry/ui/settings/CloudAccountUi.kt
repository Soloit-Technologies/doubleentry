package com.soloit.doubleentry.ui.settings

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CreditCardOff
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.soloit.doubleentry.billing.CloudBilling
import com.soloit.doubleentry.billing.CloudPlan
import com.soloit.doubleentry.sync.CloudAccess
import com.soloit.doubleentry.sync.SignInQuestion
import com.soloit.doubleentry.sync.SupabaseApi
import com.soloit.doubleentry.sync.SyncManager
import com.soloit.doubleentry.system.GoogleSignIn
import com.soloit.doubleentry.ui.components.ConfirmDialog
import com.soloit.doubleentry.ui.components.SectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Signing in: Sign in with Google, which also creates the account the first time, or [EmailSignIn]
 * for accounts made with a password. [onQuestion] receives the sign-in question, if any.
 */
@Composable
internal fun ColumnScope.AccountForms(sync: SyncManager, scope: CoroutineScope, googleClientId: String, onQuestion: (SignInQuestion?) -> Unit) {
    val context = LocalActivity.current ?: LocalContext.current
    var withEmail by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    fun signIn() {
        busy = true
        error = null
        hint = null
        scope.launch {
            try {
                val result = signInWithGoogle(context, sync, googleClientId)
                if (result == null) hint = CANCELLED_HINT else onQuestion(result.question)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = SupabaseApi.userMessage(e)
            } finally {
                busy = false
            }
        }
    }

    Text("Sign in to back up", style = MaterialTheme.typography.titleMedium)
    Text(
        "Use your Google account: no new password to remember. The first sign-in creates your DoubleEntry account.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (googleClientId.isBlank()) {
        Text("Sign in with Google isn't set up in this build.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        GoogleButton(busy, onClick = ::signIn)
    }
    hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (withEmail) {
        EmailSignIn(sync, scope, onQuestion)
    } else {
        TextButton(onClick = { withEmail = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Have a password? Sign in with email", textAlign = TextAlign.Center)
        }
    }
}

/**
 * Signing in with email and password, for the few accounts made by hand in the dashboard (people
 * who can't use Google, app reviewers); nobody can sign up this way. [email] fills in the address,
 * as when a session is renewed. [onQuestion] receives the sign-in question, if any.
 */
@Composable
internal fun EmailSignIn(sync: SyncManager, scope: CoroutineScope, onQuestion: (SignInQuestion?) -> Unit, email: String = "", label: String = "Sign in") {
    var address by rememberSaveable { mutableStateOf(email) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun signIn() {
        if (address.isBlank() || password.isEmpty()) {
            error = "Enter your email and password."
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                onQuestion(sync.signIn(address, password))
                password = ""
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = SupabaseApi.userMessage(e)
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        PasswordField(password, { password = it }, onDone = ::signIn)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = ::signIn, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(label)
            }
        }
    }
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, onDone: () -> Unit) {
    var showPassword by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPassword) "Hide password" else "Show password")
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Shown after Google's sheet closed without an account. Credential Manager reports a sign-in that
 * Google refused (for example an app it doesn't recognise) the same way as the user closing the
 * sheet, so this can't say which one happened.
 */
internal const val CANCELLED_HINT =
    "No account was chosen. If the Google sheet closed by itself, check your connection and try again."

/** A finished sign-in, with the question it asks (if any). */
internal class SignInResult(val question: SignInQuestion?)

/**
 * Shows Google's account sheet and signs in with the chosen account. Returns null when the user
 * closed the sheet, otherwise [SyncManager.signInWithGoogle]'s question wrapped so "no question"
 * can be told apart from "cancelled".
 */
internal suspend fun signInWithGoogle(context: Context, sync: SyncManager, googleClientId: String): SignInResult? {
    val token = GoogleSignIn.idToken(context, googleClientId) ?: return null
    return SignInResult(sync.signInWithGoogle(token.idToken, token.nonce, token.email))
}

@Composable
internal fun GoogleButton(busy: Boolean, label: String = "Continue with Google", onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        } else {
            Icon(Icons.Rounded.AccountCircle, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }
}

/**
 * The account's backup subscription: what it includes and until when, or the plans to start one.
 * [access] is null until the server has answered.
 */
@Composable
internal fun BackupPlanCard(access: CloudAccess?, billing: CloudBilling?) {
    val uri = LocalUriHandler.current
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val until = access?.expiresAtMillis?.let { dateFormat.format(Date(it)) }
    when {
        access == null -> Unit
        access.active && access.complimentary -> SectionCard(contentPadding = PaddingValues(20.dp)) {
            PlanHeader(Icons.Rounded.CloudDone, "Cloud backup is included", "Your account has cloud backup at no cost.")
        }
        access.active && access.earlyAccess -> SectionCard(contentPadding = PaddingValues(20.dp)) {
            PlanHeader(
                Icons.Rounded.CloudDone,
                "Cloud backup is free during early access",
                "Back up and sync at no cost while DoubleEntry is in early access.",
            )
        }
        access.active -> SectionCard(contentPadding = PaddingValues(20.dp)) {
            val plan = when (access.basePlanId) {
                "yearly" -> "Yearly plan"
                "monthly" -> "Monthly plan"
                else -> "Subscription"
            }
            val detail = when {
                access.state == "in_grace_period" -> "Google Play couldn't take the latest payment. Update your payment method to keep backing up."
                access.autoRenewing -> until?.let { "Renews on $it." }
                else -> until?.let { "Cancelled: backup stops on $it. What's already backed up stays." }
            }
            PlanHeader(Icons.Rounded.CloudDone, "Cloud backup is on · $plan", detail)
            billing?.let {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { uri.openUri(it.manageUrl) }) { Text(if (access.state == "in_grace_period") "Fix payment" else "Manage subscription") }
            }
        }
        access.state == "on_hold" && billing != null -> SectionCard(contentPadding = PaddingValues(20.dp), containerColor = MaterialTheme.colorScheme.errorContainer) {
            PlanHeader(
                Icons.Rounded.CreditCardOff,
                "Backup is paused",
                "Google Play couldn't take your payment, so new changes aren't backed up. Fix the payment method to continue.",
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { uri.openUri(billing.manageUrl) }) { Text("Fix payment in Google Play") }
        }
        else -> PlansCard(access, billing)
    }
}

@Composable
private fun PlansCard(access: CloudAccess, billing: CloudBilling?) {
    val activity = LocalActivity.current
    SectionCard(contentPadding = PaddingValues(20.dp)) {
        val ended = access.source != null
        Text(if (ended) "Your backup has ended" else "Start backing up", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            if (ended) {
                "New changes stay on this device until you subscribe again. Everything backed up before is still in the cloud."
            } else {
                "Your account is ready. Choose a plan to back up this ledger and sync it across your devices."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (billing == null) {
            Text("Subscriptions aren't available in this build.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        val state by billing.state.collectAsStateWithLifecycle()
        LaunchedEffect(billing) { billing.loadPlans() }
        val scope = androidx.compose.runtime.rememberCoroutineScope()

        when {
            state.confirming -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Confirming your purchase…")
            }
            state.pending -> PlanHeader(
                Icons.Rounded.HourglassTop,
                "Payment pending",
                "Backup starts as soon as Google Play confirms your payment.",
            )
            state.loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            !state.available -> Text(
                "Subscriptions can't be loaded right now. Check that this device is signed in to Google Play and online.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.plans.forEachIndexed { i, plan ->
                    PlanButton(plan, primary = i == 0) { activity?.let { billing.purchase(it, plan) } }
                }
            }
        }
        state.message?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { billing.clearMessage(); scope.launch { billing.restore(everything = true, userAsked = true) } }) {
            Text("Restore purchase")
        }
        Text(
            "Renews automatically. Billed through Google Play; cancel anytime there.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlanButton(plan: CloudPlan, primary: Boolean, onClick: () -> Unit) {
    val name = when (plan.period) {
        "P1Y" -> "Yearly"
        "P1M" -> "Monthly"
        else -> plan.basePlanId.replaceFirstChar { it.uppercase() }
    }
    val price = "${plan.price} / ${periodText(plan.period)}"
    val trial = plan.freeTrial?.let { "${durationText(it)} free, then $price" }
    val content = @Composable {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(name, fontWeight = FontWeight.SemiBold)
            Text(trial ?: price, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (primary) Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
    else OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun PlanHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String?) {
    Row {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** "P1M" → "month", "P3M" → "3 months", "P1Y" → "year". */
internal fun periodText(period: String): String {
    val (count, unit) = parsePeriod(period) ?: return period
    return if (count == 1) unit else "$count ${unit}s"
}

/** "P14D" → "14 days", "P1W" → "1 week", "P1M" → "1 month". */
internal fun durationText(period: String): String {
    val (count, unit) = parsePeriod(period) ?: return period
    return "$count $unit${if (count == 1) "" else "s"}"
}

private fun parsePeriod(period: String): Pair<Int, String>? {
    val match = Regex("""P(\d+)([DWMY])""").matchEntire(period) ?: return null
    val unit = when (match.groupValues[2]) {
        "D" -> "day"
        "W" -> "week"
        "M" -> "month"
        else -> "year"
    }
    return match.groupValues[1].toInt() to unit
}

/** Confirms deleting the account and its cloud copy; the ledger on this device stays. */
@Composable
internal fun DeleteAccountDialog(email: String?, paidThroughPlay: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ConfirmDialog(
        title = "Delete your account?",
        message = "This permanently deletes ${email ?: "your account"} and everything backed up in the cloud. " +
            "Other devices signed in to it stop syncing. The ledger on this device stays." +
            if (paidThroughPlay) "\n\nDeleting the account doesn't cancel your Google Play subscription. Cancel it in Google Play so you aren't charged again." else "",
        confirmLabel = "Delete account",
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
