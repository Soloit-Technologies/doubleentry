package com.soloit.doubleentry.data

import com.soloit.doubleentry.data.local.AppDatabase
import com.soloit.doubleentry.data.local.EntryEntity
import com.soloit.doubleentry.data.local.TransactionEntity
import com.soloit.doubleentry.domain.AccountType
import com.soloit.doubleentry.domain.CostPeriod
import com.soloit.doubleentry.domain.DeterministicIds
import com.soloit.doubleentry.domain.DraftLine
import com.soloit.doubleentry.domain.EntryType
import com.soloit.doubleentry.domain.LedgerException
import com.soloit.doubleentry.domain.Periodization
import com.soloit.doubleentry.domain.TransactionDraft
import com.soloit.doubleentry.domain.label
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.YearMonth

/** How a transaction's lines are spread over periods, for showing it. */
data class CostPeriodSummary(val sourceId: String, val lines: List<Line>) {
    data class Line(val accountId: String, val amount: Long, val type: EntryType, val period: CostPeriod, val shares: List<Share>)

    /** [postingId] is null for the share that stays in the transaction's own month. */
    data class Share(val month: YearMonth, val amount: Long, val postingId: String?)
}

/**
 * Stores lines that carry a [CostPeriod]. The transaction itself holds the expanded lines (own
 * month on the line's account, the rest on interim accounts), one posting per other month moves
 * a share back, and a synced setting (`CostPeriod.Transaction.<id>`) remembers the original lines
 * so the transaction can be edited as entered. Posting ids derive from (transaction, month), so
 * an edit updates the same postings in place.
 *
 * Every method runs inside the caller's database transaction.
 */
internal class CostPeriods(private val db: AppDatabase, private val settings: SettingsRepository) {
    private val transactionDao = db.transactions()
    private val entryDao = db.entries()

    /** What saving a draft writes: the transaction's own lines, its postings and the record of the period lines. */
    class Plan(val sourceLines: List<DraftLine>, val postings: List<TransactionDraft>, val record: Record?)

    /** Expands the period lines of [draft]; fails when an interim account it needs isn't set up. */
    suspend fun plan(id: String, draft: TransactionDraft): Plan {
        if (draft.lines.none { it.period != null }) return Plan(draft.lines, emptyList(), null)
        val types = db.accounts().byIds(draft.lines.map { it.accountId }.distinct())
            .filter { it.deletedAt == null }
            .associate { it.id to AccountType.fromOrdinal(it.type) }
        for (line in draft.lines) {
            val period = line.period ?: continue
            Periodization.validate(period)
            val type = types[line.accountId] ?: throw LedgerException("One of the selected accounts no longer exists.")
            if (!Periodization.canHavePeriod(type)) throw LedgerException("Only expense and income lines can be spread over a period.")
        }
        val interims = interimAccountIds(Periodization.interimsNeeded(draft.lines, types, draft.date))
        val expansion = Periodization.expand(draft, types, interims)

        val postings = expansion.postings.map { (month, lines) ->
            val postingId = DeterministicIds.periodTransactionId(id, month)
            TransactionDraft(
                id = postingId,
                date = month.atEndOfMonth(),
                title = draft.title,
                description = "Share for ${month.label()}",
                lines = lines.mapIndexed { i, line -> line.copy(id = DeterministicIds.periodEntryId(postingId, i)) },
            )
        }
        val record = Record(
            draft.lines.mapIndexedNotNull { index, line ->
                val period = line.period ?: return@mapIndexedNotNull null
                LineJson(
                    accountId = line.accountId,
                    amount = line.amount,
                    type = line.type.ordinal,
                    start = period.start.toString(),
                    end = period.end.toString(),
                    parts = expansion.parts[index].orEmpty().map { PartJson(it.accountId, it.amount) },
                )
            },
        )
        return Plan(expansion.sourceLines, postings, record)
    }

    /**
     * Writes [plan]'s postings and record for [sourceId] and removes postings an earlier version
     * of the transaction had that [plan] no longer needs.
     */
    suspend fun apply(sourceId: String, plan: Plan, now: Long) {
        val keep = plan.postings.mapNotNull { it.id }.toSet()
        read(sourceId)?.let { old ->
            for (postingId in postingIds(sourceId, old)) if (postingId !in keep) transactionDao.softDelete(postingId, now)
        }
        for (posting in plan.postings) {
            val postingId = posting.id!!
            // Overwrites an earlier version of the posting, reviving it if that month had been dropped.
            transactionDao.upsert(TransactionEntity(postingId, posting.date, posting.title.trim(), posting.description, now))
            entryDao.replaceOf(postingId, posting.lines.map { EntryEntity(it.id!!, postingId, it.accountId, it.amount, it.type.ordinal) })
        }
        settings.put(KEY_PREFIX + sourceId, plan.record?.let { json.encodeToString(Record.serializer(), it) })
    }

    /** Deletes [sourceId]'s postings and record with [stamp], so undoing the delete restores them too. */
    suspend fun remove(sourceId: String, stamp: Long) {
        val record = read(sourceId) ?: return
        for (postingId in postingIds(sourceId, record)) transactionDao.softDelete(postingId, stamp)
        settings.softDelete(KEY_PREFIX + sourceId, stamp)
    }

    /** The lines of [sourceId] as entered, period lines included; null when it has none or they can't be matched. */
    suspend fun editableLines(sourceId: String, entries: List<DraftLine>): List<DraftLine>? {
        val record = read(sourceId) ?: return null
        return Periodization.collapse(
            entries,
            record.lines.map { it.toDraftLine() to it.parts.map { p -> DraftLine(p.accountId, p.amount, EntryType.fromOrdinal(it.type)) } },
        )
    }

    suspend fun summary(sourceId: String, date: LocalDate): CostPeriodSummary? {
        val record = read(sourceId) ?: return null
        val types = db.accounts().byIds(record.lines.map { it.accountId }.distinct()).associate { it.id to AccountType.fromOrdinal(it.type) }
        return CostPeriodSummary(
            sourceId,
            record.lines.map { line ->
                val draft = line.toDraftLine()
                val type = types[line.accountId] ?: AccountType.Expense
                CostPeriodSummary.Line(
                    line.accountId, line.amount, draft.type, draft.period!!,
                    Periodization.shares(line.amount, draft.period).map { share ->
                        val posted = Periodization.interimFor(type, share.month, date) != null
                        CostPeriodSummary.Share(share.month, share.amount, if (posted) DeterministicIds.periodTransactionId(sourceId, share.month) else null)
                    },
                )
            },
        )
    }

    /** The transaction whose period lines [postingId] belongs to, if it is a period posting. */
    suspend fun sourceOf(postingId: String): String? =
        settings.livePrefix(KEY_PREFIX).entries.firstOrNull { (sourceId, raw) ->
            decode(raw)?.let { postingId in postingIds(sourceId, it) } == true
        }?.key

    private suspend fun read(sourceId: String): Record? = settings.get(KEY_PREFIX + sourceId)?.let(::decode)

    /** Every id a posting of [record] can have: one per month the periods touch. */
    private fun postingIds(sourceId: String, record: Record): Set<String> =
        record.lines.flatMap { it.toDraftLine().period!!.months }.toSet()
            .mapTo(mutableSetOf()) { DeterministicIds.periodTransactionId(sourceId, it) }

    /**
     * The live account chosen in settings for each of [needed]. There are no defaults and interim
     * accounts are never created here: when one isn't chosen the user is asked to choose it.
     */
    private suspend fun interimAccountIds(needed: Set<Periodization.Interim>): Map<Periodization.Interim, String> {
        if (needed.isEmpty()) return emptyMap()
        val accounts = settings.getCostPeriodAccounts()
        val missing = needed.sorted().filter { accounts.numberFor(it) == null }
        if (missing.isNotEmpty()) {
            val names = missing.map { it.description }
            val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
            val noun = if (names.size == 1) "an account" else "accounts"
            throw LedgerException("Choose $noun for $list in Settings > Cost periods.")
        }
        val live = db.accounts().live()
        return needed.associateWith { interim ->
            val number = accounts.numberFor(interim)!!
            live.firstOrNull { it.number == number }?.id
                ?: throw LedgerException("Account #$number, chosen for cost periods in Settings, no longer exists.")
        }
    }

    private val Periodization.Interim.description: String
        get() = when (this) {
            Periodization.Interim.PrepaidExpenses -> "prepaid expenses"
            Periodization.Interim.AccruedExpenses -> "accrued expenses"
            Periodization.Interim.AccruedIncome -> "accrued income"
            Periodization.Interim.DeferredIncome -> "income received in advance"
        }

    @Serializable
    internal data class Record(@SerialName("Lines") val lines: List<LineJson>)

    /** A period line as entered; [type] is the [EntryType] ordinal and [parts] the lines it became in the transaction. */
    @Serializable
    internal data class LineJson(
        @SerialName("AccountId") val accountId: String,
        @SerialName("Amount") val amount: Long,
        @SerialName("Type") val type: Int,
        @SerialName("Start") val start: String,
        @SerialName("End") val end: String,
        @SerialName("Parts") val parts: List<PartJson> = emptyList(),
    ) {
        fun toDraftLine() = DraftLine(accountId, amount, EntryType.fromOrdinal(type), period = CostPeriod(LocalDate.parse(start), LocalDate.parse(end)))
    }

    @Serializable
    internal data class PartJson(
        @SerialName("AccountId") val accountId: String,
        @SerialName("Amount") val amount: Long,
    )

    /** A stored period line: the line as entered (with its period) and the lines it became in the transaction. */
    data class StoredLine(val line: DraftLine, val parts: List<DraftLine>)

    companion object {
        const val KEY_PREFIX = SettingsRepository.Keys.COST_PERIOD_PREFIX

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private fun decode(raw: String): Record? = runCatching { json.decodeFromString(Record.serializer(), raw) }.getOrNull()

        /** The period lines of a `CostPeriod.Transaction.<id>` setting; used by backups. */
        fun decodeLines(raw: String): List<StoredLine>? = decode(raw)?.lines?.map { l ->
            StoredLine(l.toDraftLine(), l.parts.map { DraftLine(it.accountId, it.amount, EntryType.fromOrdinal(l.type)) })
        }

        fun encodeLines(lines: List<StoredLine>): String = json.encodeToString(
            Record.serializer(),
            Record(
                lines.map { (line, parts) ->
                    val period = requireNotNull(line.period) { "A stored period line needs its period." }
                    LineJson(line.accountId, line.amount, line.type.ordinal, period.start.toString(), period.end.toString(), parts.map { PartJson(it.accountId, it.amount) })
                },
            ),
        )

        /** Months the postings of [lines] can be in: every month their periods touch. */
        fun postingMonths(lines: List<StoredLine>): Set<YearMonth> = lines.flatMap { it.line.period!!.months }.toSet()
    }
}
