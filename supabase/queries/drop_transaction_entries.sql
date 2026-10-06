-- =============================================================================
-- Drop the old transaction_entries table.
--
-- Since 20261006000002_transaction_lines_and_edit_times.sql the lines of a transaction live in
-- transactions.lines, and that migration copied every line over. The old table is unused but
-- kept, because the earlier migrations recreate and alter it when they are re-run.
--
-- Run this once every device runs a build from after that migration. It cannot be undone.
-- Runs in a transaction; review the counts before committing.
-- =============================================================================

begin;

-- Transactions whose lines were not copied (should be 0).
select count(*) as transactions_without_lines
from public.transactions t
where jsonb_array_length(t.lines) = 0
  and exists (select 1 from public.transaction_entries e
              where e.user_id = t.user_id and e.transaction_id = t.id and e.deleted_at is null);

drop table public.transaction_entries;

-- Inspect, then run `commit;` (or `rollback;` to undo).
