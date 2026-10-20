package com.soloit.doubleentry.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.soloit.doubleentry.domain.Account
import com.soloit.doubleentry.domain.CostPeriod
import com.soloit.doubleentry.domain.Money
import com.soloit.doubleentry.domain.Periodization
import com.soloit.doubleentry.ui.theme.AppTheme
import com.soloit.doubleentry.ui.theme.tabular
import java.time.LocalDate

@Composable
fun LinesEditor(
    lines: List<LineInput>,
    accounts: Map<String, Account>,
    onPickAccount: (Long) -> Unit,
    onDebitChange: (Long, String) -> Unit,
    onCreditChange: (Long, String) -> Unit,
    onFocusDebit: (Long) -> Unit,
    onFocusCredit: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    /** Why locked lines can't be changed (read by screen readers). */
    lockedReason: String = "Fixed by invoice settings",
    /** The transaction date; when set, expense and income lines can be spread over a period. */
    periodDate: LocalDate? = null,
    onPeriodChange: (Long, CostPeriod?) -> Unit = { _, _ -> },
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        lines.forEachIndexed { index, line ->
            LineCard(
                index = index,
                line = line,
                account = line.accountId?.let(accounts::get),
                canRemove = !line.locked && lines.size > 1,
                onPickAccount = { onPickAccount(line.key) },
                onDebitChange = { onDebitChange(line.key, it) },
                onCreditChange = { onCreditChange(line.key, it) },
                onFocusDebit = { onFocusDebit(line.key) },
                onFocusCredit = { onFocusCredit(line.key) },
                onRemove = { onRemove(line.key) },
                lockedReason = lockedReason,
                periodDate = periodDate,
                onPeriodChange = { onPeriodChange(line.key, it) },
            )
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Add, null)
            Spacer(Modifier.width(8.dp))
            Text("Add line")
        }
        BalanceSummary(lines)
    }
}

@Composable
private fun LineCard(
    index: Int,
    line: LineInput,
    account: Account?,
    canRemove: Boolean,
    onPickAccount: () -> Unit,
    onDebitChange: (String) -> Unit,
    onCreditChange: (String) -> Unit,
    onFocusDebit: () -> Unit,
    onFocusCredit: () -> Unit,
    onRemove: () -> Unit,
    lockedReason: String,
    periodDate: LocalDate?,
    onPeriodChange: (CostPeriod?) -> Unit,
) {
    SectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.weight(1f).clickable(enabled = !line.locked, onClick = onPickAccount),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (account != null) AccountTypeAvatar(account.type, size = 32) else IconAvatar(Icons.Rounded.UnfoldMore, MaterialTheme.colorScheme.onSurfaceVariant, size = 32)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            account?.name ?: "Line ${index + 1}: choose account",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (account == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            account?.let { "#${it.number} · ${it.type.name}" } ?: "Tap to select",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (line.locked) Icon(Icons.Rounded.Lock, lockedReason, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            if (canRemove) {
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.DeleteOutline, "Remove line") }
            }
        }
        Spacer(Modifier.size(8.dp))
        FieldRow {
            AmountField(
                value = line.debit,
                onValueChange = onDebitChange,
                label = "Debit",
                enabled = !line.locked && line.creditCents == null,
                modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) onFocusDebit() },
            )
            AmountField(
                value = line.credit,
                onValueChange = onCreditChange,
                label = "Credit",
                enabled = !line.locked && line.debitCents == null,
                modifier = Modifier.weight(1f).onFocusChanged { if (it.isFocused) onFocusCredit() },
            )
        }
        // A period stays visible if the account changes, so it can be seen and removed.
        if (periodDate != null && !line.locked && (line.period != null || account?.type?.let(Periodization::canHavePeriod) == true)) {
            CostPeriodInput(
                period = line.period,
                amount = line.debitCents ?: line.creditCents,
                date = periodDate,
                onChange = onPeriodChange,
            )
        }
    }
}

/** Debit/credit totals with a balanced/unbalanced indicator. */
@Composable
fun BalanceSummary(lines: List<LineInput>) {
    val (debits, credits) = Lines.totals(lines)
    val finance = AppTheme.finance
    val empty = debits == 0L && credits == 0L
    val balanced = debits == credits
    val container by animateColorAsState(
        when {
            empty -> MaterialTheme.colorScheme.surfaceContainerHigh
            balanced -> finance.positiveContainer
            else -> finance.warningContainer
        },
        label = "balance",
    )
    val content = when {
        empty -> MaterialTheme.colorScheme.onSurfaceVariant
        balanced -> finance.onPositiveContainer
        else -> finance.onWarningContainer
    }
    Surface(shape = MaterialTheme.shapes.medium, color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (balanced) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        empty -> "Enter amounts"
                        balanced -> "Balanced"
                        debits > credits -> "Debits exceed credits by ${Money.format(debits - credits)}"
                        else -> "Credits exceed debits by ${Money.format(credits - debits)}"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "Debit ${Money.format(debits)}  ·  Credit ${Money.format(credits)}",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}
