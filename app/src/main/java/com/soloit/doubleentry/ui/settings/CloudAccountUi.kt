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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CreditCardOff
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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

private enum class AuthStep { Start, EmailSignIn, ForgotPassword, ResetPassword }

/**
 * Signing in: Sign in with Google for everyone (it also creates the account), and email with
 * password only for accounts made before that, including resetting a forgotten password with an
 * emailed code. [onQuestion] receives the sign-in question, if any.
 */
@Composable
internal fun ColumnScope.AccountForms(sync: SyncManager, scope: CoroutineScope, googleClientId: String, onQuestion: (SignInQuestion?) -> Unit) {
    val context = LocalActivity.current ?: LocalContext.current
    var step by rememberSaveable { mutableStateOf(AuthStep.Start) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun go(to: AuthStep, message: String? = null) {
        step = to
        error = null
        info = message
        code = ""
    }

    /** Runs [block] with the busy spinner; errors are shown under the form. */
    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = SupabaseApi.userMessage(e)
            } finally {
                busy = false
            }
        }
    }

    fun require(vararg values: String, message: String): Boolean {
        if (values.any { it.isBlank() }) {
            error = message
            return false
        }
        return true
    }

    val title = when (step) {
        AuthStep.Start -> "Sign in to back up"
        AuthStep.EmailSignIn -> "Sign in with email"
        AuthStep.ForgotPassword -> "Reset your password"
        AuthStep.ResetPassword -> "Choose a new password"
    }
    Text(title, style = MaterialTheme.typography.titleMedium)
    info?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

    val emailField = @Composable {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    when (step) {
        AuthStep.Start -> {
            Text(
                "Use your Google account: no new password to remember. The first sign-in creates your DoubleEntry account.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (googleClientId.isBlank()) {
                Text("Sign in with Google isn't set up in this build.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                GoogleButton(busy) { run { onQuestion((signInWithGoogle(context, sync, googleClientId) ?: return@run).question) } }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { go(AuthStep.EmailSignIn) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Have an account with a password? Sign in with email")
            }
        }
        AuthStep.EmailSignIn -> {
            val signIn = {
                if (require(email, password, message = "Email and password are required.")) {
                    run { onQuestion(sync.signIn(email, password)); password = "" }
                }
            }
            emailField()
            PasswordField(password, { password = it }, onDone = signIn)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            BusyButton("Sign in", busy, onClick = signIn)
            Row {
                TextButton(onClick = { go(AuthStep.Start) }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { go(AuthStep.ForgotPassword) }) { Text("Forgot password?") }
            }
        }
        AuthStep.ForgotPassword -> {
            val send = {
                if (require(email, message = "Enter your email.")) {
                    run {
                        sync.requestPasswordReset(email)
                        go(AuthStep.ResetPassword, "If ${email.trim()} has an account, we sent it a 6-digit code.")
                    }
                }
            }
            emailField()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            BusyButton("Send code", busy, onClick = send)
            TextButton(onClick = { go(AuthStep.EmailSignIn) }) { Text("Back") }
        }
        AuthStep.ResetPassword -> {
            val reset = {
                if (require(code, password, message = "Enter the code and a new password.")) {
                    run { onQuestion(sync.resetPassword(email, code, password)); password = "" }
                }
            }
            OutlinedTextField(
                value = code,
                onValueChange = { value -> code = value.filter(Char::isDigit).take(10) },
                label = { Text("6-digit code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            PasswordField(password, { password = it }, onDone = reset, label = "New password")
            Text("At least 8 characters.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            BusyButton("Save password and sign in", busy, onClick = reset)
            TextButton(onClick = { go(AuthStep.EmailSignIn) }) { Text("Back") }
        }
    }
}

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

@Composable
private fun BusyButton(label: String, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(label)
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
            "Billed through Google Play. Cancel anytime there.",
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
