-- =============================================================================
-- Migration: free cloud backup during early access, and limits on what an account can store
--
-- * Early access: every new account gets cloud backup for free (a cloud_subscriptions row with
--   source 'early_access' and no end), and so does every existing account without a row.
--   `service_control.early_access` switches this off for accounts made from then on; rows already
--   given stay until changed by hand (see supabase/README.md, Early access).
-- * Storage: each ledger may store rows, tombstones included, up to an allowance that grows with
--   its age: 5 MB to start (`service_control.ledger_quota_start_bytes`), 25 KB more each day
--   (`ledger_quota_daily_bytes`), at most 50 MB (`ledger_quota_bytes`). That leaves room to import
--   years of history at once, while no account can fill the database in a day. Counted in
--   ledger_usage by triggers on the synced tables. Over the allowance, new rows are refused with
--   `ledger_full`; edits and deletions of existing rows still go through until the ledger is 10%
--   over, so a full ledger can still be tidied up.
-- * Database size: a cron job measures the whole database every 15 minutes. Past
--   `early_access_max_database_bytes` (350 MB) it ends early access for new accounts; past
--   `max_database_bytes` (425 MB) every ledger refuses new rows with `ledger_full`. Both keep the
--   project under the plan's 500 MB, however many accounts there are.
-- * Sharing: at most 20 members per ledger and 20 open invites per ledger.
--
-- Safe to re-run.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Switches
-- -----------------------------------------------------------------------------
alter table public.service_control add column if not exists early_access boolean not null default true;
alter table public.service_control add column if not exists ledger_quota_bytes bigint not null default 52428800;
alter table public.service_control add column if not exists ledger_quota_start_bytes bigint not null default 5242880;
alter table public.service_control add column if not exists ledger_quota_daily_bytes bigint not null default 25600;
alter table public.service_control add column if not exists early_access_max_database_bytes bigint not null default 367001600;
alter table public.service_control add column if not exists max_database_bytes bigint not null default 445644800;
-- Last measured by check_database_size().
alter table public.service_control add column if not exists database_bytes bigint not null default 0;

alter table public.service_control drop constraint if exists service_control_storage_check;
alter table public.service_control add constraint service_control_storage_check check (
  ledger_quota_bytes > 0 and ledger_quota_start_bytes > 0 and ledger_quota_daily_bytes >= 0
  and early_access_max_database_bytes > 0 and max_database_bytes > 0 and database_bytes >= 0);

-- -----------------------------------------------------------------------------
-- 2. Early access
-- -----------------------------------------------------------------------------
alter table public.cloud_subscriptions drop constraint if exists cloud_subscriptions_source_check;
alter table public.cloud_subscriptions add constraint cloud_subscriptions_source_check
  check (source in ('google_play', 'complimentary', 'early_access'));

create or replace function public.grant_early_access()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if (select c.early_access from public.service_control c where c.id) then
    insert into public.cloud_subscriptions (user_id, source, state)
    values (new.id, 'early_access', 'active')
    on conflict (user_id) do nothing;
  end if;
  return new;
end;
$$;

revoke all on function public.grant_early_access() from public, anon, authenticated;

drop trigger if exists trg_users_grant_early_access on auth.users;
create trigger trg_users_grant_early_access
  after insert on auth.users
  for each row execute function public.grant_early_access();

insert into public.cloud_subscriptions (user_id, source, state)
select u.id, 'early_access', 'active' from auth.users u
where (select c.early_access from public.service_control c where c.id)
on conflict (user_id) do nothing;

-- -----------------------------------------------------------------------------
-- 3. Storage per ledger
-- -----------------------------------------------------------------------------
-- Bytes of the rows as JSON text, tombstones included, without indexes. JSON rather than
-- pg_column_size(), which measures a row just written differently from the same row read back.
-- Only the triggers below and the service role write it.
create table if not exists public.ledger_usage (
  ledger_id  uuid primary key references public.ledgers (id) on delete cascade,
  bytes      bigint not null default 0
);

alter table public.ledger_usage enable row level security;
revoke all on public.ledger_usage from anon, authenticated;

-- What a ledger made then may store now: the start allowance, plus the daily one for each day
-- since, up to the cap.
create or replace function public.ledger_allowance(ledger_created_at timestamptz)
returns bigint
language sql
stable
set search_path = ''
as $$
  select least(c.ledger_quota_bytes,
               c.ledger_quota_start_bytes + c.ledger_quota_daily_bytes * greatest(now()::date - ledger_created_at::date, 0))
  from public.service_control c where c.id;
$$;

revoke all on function public.ledger_allowance(timestamptz) from public, anon, authenticated;

-- Statement triggers with transition tables, so an upload of 500 rows updates the count once.
create or replace function public.count_ledger_usage()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  control public.service_control;
  -- The allowance of a ledger this statement took over it, if any.
  exceeded bigint;
begin
  select * into control from public.service_control c where c.id;

  if tg_op = 'DELETE' then
    -- Also runs for rows deleted with their ledger, whose usage row is already gone.
    update public.ledger_usage u set bytes = greatest(u.bytes - d.bytes, 0)
    from (select o.ledger_id, sum(octet_length(row_to_json(o.*)::text)) as bytes from old_rows o group by o.ledger_id) d
    where u.ledger_id = d.ledger_id;
    return null;
  end if;

  -- New rows need room under the allowance; edits that grow the ledger may use 10% more.
  if tg_op = 'INSERT' then
    -- The whole database is nearly full (check_database_size()): no new rows in any ledger.
    if control.database_bytes > control.max_database_bytes and exists (select from new_rows) then
      raise exception 'ledger_full' using errcode = 'P0001', detail = 'The cloud storage is full.';
    end if;

    with delta as (
      select n.ledger_id, sum(octet_length(row_to_json(n.*)::text))::bigint as bytes from new_rows n group by n.ledger_id
    ), counted as (
      insert into public.ledger_usage as u (ledger_id, bytes)
      select d.ledger_id, d.bytes from delta d
      on conflict (ledger_id) do update set bytes = u.bytes + excluded.bytes
      returning u.ledger_id, u.bytes
    )
    select a.allowance into exceeded from (
      select c.bytes, public.ledger_allowance(l.created_at) as allowance
      from counted c join public.ledgers l on l.id = c.ledger_id
    ) a
    where a.bytes > a.allowance limit 1;
  else
    with delta as (
      select d.ledger_id, sum(d.bytes)::bigint as bytes from (
        select n.ledger_id, octet_length(row_to_json(n.*)::text)::bigint as bytes from new_rows n
        union all
        select o.ledger_id, -octet_length(row_to_json(o.*)::text)::bigint from old_rows o
      ) d group by d.ledger_id
    ), counted as (
      -- An updated row's ledger has its usage row from when the row was inserted (or counted
      -- below). Only updating also covers a deleted account's rows losing their user_id while
      -- its ledgers, and their usage, are being deleted.
      update public.ledger_usage u set bytes = greatest(u.bytes + d.bytes, 0)
      from delta d where u.ledger_id = d.ledger_id
      returning u.ledger_id, u.bytes, d.bytes as grown
    )
    select a.allowance into exceeded from (
      select c.bytes, c.grown, public.ledger_allowance(l.created_at) as allowance
      from counted c join public.ledgers l on l.id = c.ledger_id
    ) a
    where a.grown > 0 and a.bytes > a.allowance + a.allowance / 10 limit 1;
  end if;

  if exceeded is not null then
    raise exception 'ledger_full' using errcode = 'P0001',
      detail = format('This ledger has used its %s of cloud storage for now.', pg_size_pretty(exceeded));
  end if;
  return null;
end;
$$;

revoke all on function public.count_ledger_usage() from public, anon, authenticated;

do $$
declare
  t text;
begin
  foreach t in array array['accounts', 'transactions', 'assets', 'invoices', 'user_settings']
  loop
    execute format('drop trigger if exists %I on public.%I', 'trg_' || t || '_usage_insert', t);
    execute format(
      'create trigger %I after insert on public.%I referencing new table as new_rows for each statement execute function public.count_ledger_usage()',
      'trg_' || t || '_usage_insert', t);
    execute format('drop trigger if exists %I on public.%I', 'trg_' || t || '_usage_update', t);
    execute format(
      'create trigger %I after update on public.%I referencing old table as old_rows new table as new_rows for each statement execute function public.count_ledger_usage()',
      'trg_' || t || '_usage_update', t);
    execute format('drop trigger if exists %I on public.%I', 'trg_' || t || '_usage_delete', t);
    execute format(
      'create trigger %I after delete on public.%I referencing old table as old_rows for each statement execute function public.count_ledger_usage()',
      'trg_' || t || '_usage_delete', t);
  end loop;
end;
$$;

-- What the ledgers hold now. Re-running recounts.
insert into public.ledger_usage (ledger_id, bytes)
select r.ledger_id, sum(r.bytes) from (
  select a.ledger_id, octet_length(row_to_json(a.*)::text)::bigint as bytes from public.accounts a
  union all select t.ledger_id, octet_length(row_to_json(t.*)::text) from public.transactions t
  union all select s.ledger_id, octet_length(row_to_json(s.*)::text) from public.assets s
  union all select i.ledger_id, octet_length(row_to_json(i.*)::text) from public.invoices i
  union all select u.ledger_id, octet_length(row_to_json(u.*)::text) from public.user_settings u
) r
group by r.ledger_id
on conflict (ledger_id) do update set bytes = excluded.bytes;

-- -----------------------------------------------------------------------------
-- 4. Members and invites per ledger
-- -----------------------------------------------------------------------------
create or replace function public.limit_ledger_sharing()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  -- One ledger at a time, so two invites accepted at once can't both take the last place.
  perform pg_advisory_xact_lock(hashtextextended('ledger_sharing:' || new.ledger_id::text, 0));
  if tg_table_name = 'ledger_members' then
    if (select count(*) from public.ledger_members m where m.ledger_id = new.ledger_id) >= 20 then
      raise exception 'too_many_members' using errcode = 'P0001';
    end if;
  elsif (select count(*) from public.ledger_invites i
         where i.ledger_id = new.ledger_id and i.accepted_at is null and i.expires_at > now()) >= 20 then
    raise exception 'too_many_invites' using errcode = 'P0001';
  end if;
  return new;
end;
$$;

revoke all on function public.limit_ledger_sharing() from public, anon, authenticated;

drop trigger if exists trg_ledger_members_limit on public.ledger_members;
create trigger trg_ledger_members_limit
  before insert on public.ledger_members
  for each row execute function public.limit_ledger_sharing();

drop trigger if exists trg_ledger_invites_limit on public.ledger_invites;
create trigger trg_ledger_invites_limit
  before insert on public.ledger_invites
  for each row execute function public.limit_ledger_sharing();

-- Invites that can no longer be used are only kept a while, for support questions.
create extension if not exists pg_cron;
select cron.schedule('purge-ledger-invites', '23 3 * * *',
  $$delete from public.ledger_invites where expires_at < now() - interval '30 days'$$);

-- -----------------------------------------------------------------------------
-- 5. Size of the whole database
-- -----------------------------------------------------------------------------
-- The ledgers' counts leave out indexes, row headers, dead rows and the other tables, so what the
-- plan's limit applies to is measured instead. Early access, once ended here, stays ended until it's
-- switched on again by hand. New rows are taken again as soon as a measurement is back under
-- max_database_bytes (or the limit is raised).
create or replace function public.check_database_size()
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  size bigint := pg_database_size(current_database());
begin
  update public.service_control c set
    database_bytes = size,
    early_access = c.early_access and size <= c.early_access_max_database_bytes
  where c.id;
end;
$$;

revoke all on function public.check_database_size() from public, anon, authenticated;

select cron.schedule('check-database-size', '*/15 * * * *', $$select public.check_database_size()$$);
select public.check_database_size();
