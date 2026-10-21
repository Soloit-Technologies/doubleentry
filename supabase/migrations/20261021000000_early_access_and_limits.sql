-- =============================================================================
-- Migration: free cloud backup during early access, and limits on what an account can store
--
-- * Early access: every new account gets cloud backup for free (a cloud_subscriptions row with
--   source 'early_access' and no end), and so does every existing account without a row.
--   `service_control.early_access` switches this off for accounts made from then on; rows already
--   given stay until changed by hand (see supabase/README.md, Early access).
-- * Storage: each ledger may store `service_control.ledger_quota_bytes` (100 MB) of rows,
--   tombstones included, counted in ledger_usage by triggers on the synced tables. Over the quota,
--   new rows are refused with `ledger_full`; edits and deletions of existing rows still go through
--   until the ledger is 10% over, so a full ledger can still be tidied up.
-- * Sharing: at most 20 members per ledger and 20 open invites per ledger.
--
-- Safe to re-run.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Switches
-- -----------------------------------------------------------------------------
alter table public.service_control add column if not exists early_access boolean not null default true;
alter table public.service_control add column if not exists ledger_quota_bytes bigint not null default 104857600;

do $$
begin
  alter table public.service_control add constraint service_control_ledger_quota_bytes_check check (ledger_quota_bytes > 0);
exception when duplicate_object then null;
end;
$$;

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

-- Statement triggers with transition tables, so an upload of 500 rows updates the count once.
create or replace function public.count_ledger_usage()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  quota bigint := (select c.ledger_quota_bytes from public.service_control c where c.id);
  over boolean;
begin
  if tg_op = 'DELETE' then
    -- Also runs for rows deleted with their ledger, whose usage row is already gone.
    update public.ledger_usage u set bytes = greatest(u.bytes - d.bytes, 0)
    from (select o.ledger_id, sum(octet_length(row_to_json(o.*)::text)) as bytes from old_rows o group by o.ledger_id) d
    where u.ledger_id = d.ledger_id;
    return null;
  end if;

  -- New rows need room under the quota; edits that grow the ledger may use 10% more.
  if tg_op = 'INSERT' then
    with delta as (
      select n.ledger_id, sum(octet_length(row_to_json(n.*)::text))::bigint as bytes from new_rows n group by n.ledger_id
    ), counted as (
      insert into public.ledger_usage as u (ledger_id, bytes)
      select d.ledger_id, d.bytes from delta d
      on conflict (ledger_id) do update set bytes = u.bytes + excluded.bytes
      returning u.bytes
    )
    select coalesce(bool_or(c.bytes > quota), false) into over from counted c;
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
      returning u.bytes, d.bytes as grown
    )
    select coalesce(bool_or(c.grown > 0 and c.bytes > quota + quota / 10), false) into over from counted c;
  end if;

  if over then
    raise exception 'ledger_full' using errcode = 'P0001',
      detail = format('This ledger has used its %s of cloud storage.', pg_size_pretty(quota));
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
