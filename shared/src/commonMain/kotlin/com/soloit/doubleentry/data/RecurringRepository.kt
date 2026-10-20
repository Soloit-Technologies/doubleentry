package com.soloit.doubleentry.data

import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.withTransaction
import com.soloit.doubleentry.domain.DeterministicIds
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.Frequency
import com.soloit.doubleentry.domain.LedgerException
import com.soloit.doubleentry.domain.Recurrence
import com.soloit.doubleentry.domain.RecurringRule
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.domain.newId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Recurring transactions. Each rule is a synced setting (`Recurring.<id>`, JSON) and every
 * occurrence is posted with an id derived from (rule, date), like depreciation, so devices that
 * post the same occurrence independently converge on one transaction.
 */
class RecurringRepository(
    private val db: AppDatabase,
    private val ledger: LedgerRepository,
    private val settings: SettingsRepository,
    private val sync: SyncTrigger,
) {
    private val lock = Mutex()

    val rules: Flow<List<RecurringRule>> = settings.observePrefix(PREFIX).map { decodeAll(it) }

    suspend fun getRules(): List<RecurringRule> = decodeAll(settings.livePrefix(PREFIX))

    suspend fun getRule(id: String): RecurringRule? = settings.get(PREFIX + id)?.let { decode(id, it) }

    /**
     * Creates or updates a rule and posts any occurrences that are already due. Editing a rule
     * changes future occurrences only; ones already posted stay as they are.
     */
    suspend fun save(rule: RecurringRule, today: LocalDate = LocalDate.now()): RecurringRule {
        Recurrence.validate(rule)
        // Keep the posting position: a changed schedule never back-fills dates that were already handled.
        // A new rule may bring its own (e.g. one made from an existing transaction counts that as posted).
        val lastPosted = getRule(rule.id)?.lastPostedDate ?: rule.lastPostedDate
        val stored = rule.copy(title = rule.title.trim(), description = rule.description.trim(), lastPostedDate = lastPosted)
        settings.put(PREFIX + stored.id, encode(stored))
        process(today)
        return getRule(stored.id) ?: stored
    }

    /** Stops the rule; transactions it already posted stay. Returns the rule so the delete can be undone with [restore]. */
    suspend fun delete(id: String): RecurringRule? {
        val rule = getRule(id)
        settings.put(PREFIX + id, null)
        return rule
    }

    suspend fun restore(rule: RecurringRule) {
        settings.put(PREFIX + rule.id, encode(rule))
        process()
    }

    suspend fun setPaused(id: String, paused: Boolean) {
        val rule = getRule(id) ?: throw LedgerException("Recurring transaction not found.")
        settings.put(PREFIX + id, encode(rule.copy(paused = paused)))
        if (!paused) process()
    }

    /**
     * Posts every occurrence that has become due. A rule whose accounts no longer exist is skipped
     * (and not advanced) so it catches up once fixed. Returns the number of transactions posted.
     */
    suspend fun process(today: LocalDate = LocalDate.now()): Int = lock.withLock {
        var posted = 0
        for (rule in getRules()) {
            val due = Recurrence.due(rule, today)
            if (due.isEmpty()) continue
            runCatching {
                db.withTransaction {
                    for (date in due) {
                        val txId = DeterministicIds.recurringTransactionId(rule.id, date)
                        // Skip occurrences that exist, including deleted ones: deleting a posting means "skip it".
                        if (db.transactions().byId(txId) != null) continue
                        ledger.insertTransaction(draftFor(rule, date, txId))
                        posted++
                    }
                    settings.put(PREFIX + rule.id, encode(rule.copy(lastPostedDate = due.last())))
                }
            }
        }
        if (posted > 0) sync.requestSync()
        posted
    }

    /** The rule that posted [transactionId], if it was posted by one. */
    suspend fun ruleForTransaction(transactionId: String): RecurringRule? = getRules().firstOrNull { rule ->
        val through = rule.lastPostedDate ?: return@firstOrNull false
        Recurrence.occurrences(rule, through).any { DeterministicIds.recurringTransactionId(rule.id, it) == transactionId }
    }

    private fun draftFor(rule: RecurringRule, date: LocalDate, txId: String) = TransactionDraft(
        id = txId,
        date = date,
        title = rule.title,
        description = rule.description,
        lines = rule.lines.mapIndexed { i, line -> line.copy(id = DeterministicIds.recurringEntryId(txId, i)) },
    )

    @Serializable
    internal data class RuleJson(
        @SerialName("Title") val title: String = "",
        @SerialName("Description") val description: String = "",
        @SerialName("Lines") val lines: List<LineJson> = emptyList(),
        @SerialName("Frequency") val frequency: String = Frequency.Monthly.name,
        @SerialName("StartDate") val startDate: String,
        @SerialName("EndDate") val endDate: String? = null,
        @SerialName("LastPostedDate") val lastPostedDate: String? = null,
        @SerialName("Paused") val paused: Boolean = false,
    )

    /** One line; [amount] is in cents and [type] is the [EntryType] ordinal. */
    @Serializable
    internal data class LineJson(
        @SerialName("AccountId") val accountId: String,
        @SerialName("Amount") val amount: Long,
        @SerialName("Type") val type: Int,
    )

    companion object {
        const val PREFIX = SettingsRepository.Keys.RECURRING_PREFIX

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun newRuleId(): String = newId()

        internal fun encode(rule: RecurringRule): String = json.encodeToString(
            RuleJson.serializer(),
            RuleJson(
                title = rule.title,
                description = rule.description,
                lines = rule.lines.map { LineJson(it.accountId, it.amount, it.type.ordinal) },
                frequency = rule.frequency.name,
                startDate = rule.startDate.toString(),
                endDate = rule.endDate?.toString(),
                lastPostedDate = rule.lastPostedDate?.toString(),
                paused = rule.paused,
            ),
        )

        internal fun decode(id: String, raw: String): RecurringRule? = runCatching {
            val r = json.decodeFromString(RuleJson.serializer(), raw)
            RecurringRule(
                id = id,
                title = r.title,
                description = r.description,
                lines = r.lines.map { DraftLine(it.accountId, it.amount, EntryType.fromOrdinal(it.type)) },
                frequency = Frequency.entries.firstOrNull { it.name == r.frequency } ?: Frequency.Monthly,
                startDate = LocalDate.parse(r.startDate),
                endDate = r.endDate?.let(LocalDate::parse),
                lastPostedDate = r.lastPostedDate?.let(LocalDate::parse),
                paused = r.paused,
            )
        }.getOrNull()

        private fun decodeAll(values: Map<String, String>): List<RecurringRule> =
            values.mapNotNull { (id, raw) -> decode(id, raw) }.sortedWith(compareBy({ it.title.lowercase() }, { it.id }))
    }
}
