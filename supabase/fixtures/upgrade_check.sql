-- =============================================================================
-- Upgrade check (see .github/workflows/supabase.yml): run with `-v phase=before` on the old
-- schema holding sample_ledgers.sql, apply the new migrations, then run with `-v phase=after`.
-- Fails when a synced row went missing, came back to life, died, or lost its edit time; or when
-- the data no longer holds together.
-- =============================================================================

\set ON_ERROR_STOP on
select :'phase' = 'before' as before_phase \gset

\if :before_phase

drop schema if exists upgrade_check cascade;
create schema upgrade_check;
-- Each synced row by what sync relies on: its key, edit time and whether it's deleted.
create table upgrade_check.rows as
  select 'accounts' as tbl, ledger_id, id, modified_at, deleted_at is not null as deleted from public.accounts
  union all select 'transactions', ledger_id, id, modified_at, deleted_at is not null from public.transactions
  union all select 'assets', ledger_id, id, modified_at, deleted_at is not null from public.assets
  union all select 'invoices', ledger_id, id, modified_at, deleted_at is not null from public.invoices
  union all select 'user_settings', ledger_id, id, modified_at, deleted_at is not null from public.user_settings;
create table upgrade_check.members as select ledger_id, user_id, role from public.ledger_members;
select count(*) as synced_rows_recorded from upgrade_check.rows;

\else

do $$
declare
  changed bigint;
begin
  with now_rows as (
    select 'accounts' as tbl, ledger_id, id, modified_at, deleted_at is not null as deleted from public.accounts
    union all select 'transactions', ledger_id, id, modified_at, deleted_at is not null from public.transactions
    union all select 'assets', ledger_id, id, modified_at, deleted_at is not null from public.assets
    union all select 'invoices', ledger_id, id, modified_at, deleted_at is not null from public.invoices
    union all select 'user_settings', ledger_id, id, modified_at, deleted_at is not null from public.user_settings
  )
  select count(distinct (d.tbl, d.ledger_id, d.id)) into changed
  from ((table upgrade_check.rows except table now_rows) union all (table now_rows except table upgrade_check.rows)) d;
  if changed > 0 then
    raise exception 'The migrations changed % synced rows (key, edit time or deletion)', changed;
  end if;

  if exists ((table upgrade_check.members) except (select ledger_id, user_id, role from public.ledger_members)) then
    raise exception 'The migrations lost or changed ledger members';
  end if;

  -- Live transactions that don't balance or have no lines.
  if exists (
    select 1 from public.transactions t
    left join lateral jsonb_to_recordset(t.lines) as e(amount numeric, entry_type smallint) on true
    where t.deleted_at is null
    group by t.ledger_id, t.id
    having count(e.amount) = 0 or sum(case when e.entry_type = 0 then e.amount else -e.amount end) <> 0
  ) then
    raise exception 'A live transaction no longer balances';
  end if;

  -- Lines of live transactions pointing at a missing account.
  if exists (
    select 1 from public.transactions t
    cross join lateral jsonb_to_recordset(t.lines) as e(account_id uuid)
    left join public.accounts a on a.ledger_id = t.ledger_id and a.id = e.account_id
    where t.deleted_at is null and a.id is null
  ) then
    raise exception 'A transaction line points at a missing account';
  end if;

  -- Every ledger keeps exactly one owner, who is its owner_id.
  if exists (
    select 1 from public.ledgers l
    where (select count(*) from public.ledger_members m where m.ledger_id = l.id and m.role = 'owner' and m.user_id = l.owner_id) <> 1
  ) then
    raise exception 'A ledger lost its owner';
  end if;
end;
$$;

select 'upgrade check passed' as result;
drop schema upgrade_check cascade;

\endif
