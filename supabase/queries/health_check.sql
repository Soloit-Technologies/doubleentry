-- =============================================================================
-- Health check: run in the Supabase SQL editor to inspect the synced data.
-- Read-only; every query covers all users of the project.
-- =============================================================================

-- Row counts per table, live vs. soft-deleted.
select 'accounts' as "table", count(*) filter (where deleted_at is null) as live, count(*) filter (where deleted_at is not null) as deleted from public.accounts
union all
select 'transactions', count(*) filter (where deleted_at is null), count(*) filter (where deleted_at is not null) from public.transactions
union all
select 'assets', count(*) filter (where deleted_at is null), count(*) filter (where deleted_at is not null) from public.assets
union all
select 'invoices', count(*) filter (where deleted_at is null), count(*) filter (where deleted_at is not null) from public.invoices
union all
select 'user_settings', count(*) filter (where deleted_at is null), count(*) filter (where deleted_at is not null) from public.user_settings;

-- Live transactions whose lines do not balance, or that have no lines (should return no rows).
select t.ledger_id, t.id, t.date, t.title,
       coalesce(sum(case when e.entry_type = 0 then e.amount else 0 end), 0) as debits,
       coalesce(sum(case when e.entry_type = 1 then e.amount else 0 end), 0) as credits
from public.transactions t
left join lateral jsonb_to_recordset(t.lines) as e(amount numeric, entry_type smallint) on true
where t.deleted_at is null
group by t.ledger_id, t.id, t.date, t.title
having count(e.amount) = 0 or sum(case when e.entry_type = 0 then e.amount else -e.amount end) <> 0
order by t.date desc;

-- Lines of live transactions that point at a deleted or missing account.
select t.ledger_id, t.id as transaction_id, t.date, t.title, e.account_id, a.deleted_at as account_deleted_at
from public.transactions t
cross join lateral jsonb_to_recordset(t.lines) as e(account_id uuid)
left join public.accounts a on a.ledger_id = t.ledger_id and a.id = e.account_id
where t.deleted_at is null and (a.id is null or a.deleted_at is not null);

-- Live accounts sharing a number (the app expects numbers to be unique per ledger).
select ledger_id, number, count(*) as copies, array_agg(name) as names
from public.accounts
where deleted_at is null
group by ledger_id, number
having count(*) > 1;
