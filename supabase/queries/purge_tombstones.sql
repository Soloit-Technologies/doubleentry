-- =============================================================================
-- Purge old tombstones (soft-deleted rows) to keep the tables small.
--
-- WARNING: a device that has been offline for longer than the retention period
-- will not learn about these deletions. Only run this when every device has synced
-- recently. Runs in a transaction; review the counts before committing.
-- =============================================================================

begin;

-- Children first so foreign keys are satisfied.
delete from public.invoices            where deleted_at < now() - interval '180 days';
delete from public.assets              where deleted_at < now() - interval '180 days';
delete from public.transaction_entries where deleted_at < now() - interval '180 days';
delete from public.transactions t
  where t.deleted_at < now() - interval '180 days'
    and not exists (select 1 from public.transaction_entries e where e.transaction_id = t.id)
    and not exists (select 1 from public.invoices i where i.booking_transaction_id = t.id or i.payment_transaction_id = t.id);
delete from public.accounts a
  where a.deleted_at < now() - interval '180 days'
    and not exists (select 1 from public.transaction_entries e where e.account_id = a.id)
    and not exists (select 1 from public.assets s where s.debit_account_id = a.id or s.credit_account_id = a.id)
    and not exists (select 1 from public.invoices i where i.outstanding_account_id = a.id or i.paid_from_account_id = a.id);
delete from public.user_settings       where deleted_at < now() - interval '180 days';

-- Inspect, then run `commit;` (or `rollback;` to undo).
