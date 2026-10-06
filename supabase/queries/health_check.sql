-- =============================================================================
-- Health check: run in the Supabase SQL editor to inspect the synced data.
-- Read-only; every query covers all users of the project.
-- =============================================================================

-- Row counts per table, live vs. soft-deleted.
select 'accounts' as "table", count(*) filter (where deleted_at is null) as live, count(*) filter (where deleted_at is not null) as deleted from public.accounts
union all
select 'transactions', count(*) filter (where deleted_at is null), count(*) filter (where deleted_at is not null) from public.transactions
union all
select 'transaction_entries', count(*) filter (where deleted_at is null), count(*) filter (where deleted_at is not null) from public.transaction_entries
union all
select 'assets', count(*) filter (where deleted_at is null), count(*) filter (where deleted_at is not null) from public.assets
union all
select 'invoices', count(*) filter (where deleted_at is null), count(*) filter (where deleted_at is not null) from public.invoices
union all
select 'user_settings', count(*) filter (where deleted_at is null), count(*) filter (where deleted_at is not null) from public.user_settings;

-- Live transactions whose live lines do not balance (should return no rows).
select t.user_id, t.id, t.date, t.title,
       sum(case when e.entry_type = 0 then e.amount else 0 end) as debits,
       sum(case when e.entry_type = 1 then e.amount else 0 end) as credits
from public.transactions t
join public.transaction_entries e on e.user_id = t.user_id and e.transaction_id = t.id and e.deleted_at is null
where t.deleted_at is null
group by t.user_id, t.id, t.date, t.title
having sum(case when e.entry_type = 0 then e.amount else -e.amount end) <> 0
order by t.date desc;

-- Live lines that belong to a deleted transaction or point at a deleted account.
select e.user_id, e.id, e.transaction_id, e.account_id, t.deleted_at as transaction_deleted_at, a.deleted_at as account_deleted_at
from public.transaction_entries e
join public.transactions t on t.user_id = e.user_id and t.id = e.transaction_id
join public.accounts a on a.user_id = e.user_id and a.id = e.account_id
where e.deleted_at is null and (t.deleted_at is not null or a.deleted_at is not null);

-- Live accounts sharing a number (the app expects numbers to be unique per user).
select user_id, number, count(*) as copies, array_agg(name) as names
from public.accounts
where deleted_at is null
group by user_id, number
having count(*) > 1;
