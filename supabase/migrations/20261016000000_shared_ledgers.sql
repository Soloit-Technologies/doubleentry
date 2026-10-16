-- =============================================================================
-- Migration: shared ledgers
--
-- A ledger is no longer the same thing as a user. Every synced row belongs to a ledger
-- (`ledger_id`), and users reach ledgers through `ledger_members`, so one ledger can have
-- several members.
--
-- * ledgers:        one row per ledger. `owner_id` is the member whose cloud backup subscription
--                   lets the ledger be uploaded to; the ledger is deleted with its owner.
-- * ledger_members: (ledger, user, role). Roles: owner (exactly one, kept in step with
--                   ledgers.owner_id), editor, viewer.
-- * The synced tables get `ledger_id`, and their primary keys and references become
--   (ledger_id, id): ids only need to be unique per ledger.
-- * `user_id` now records who last wrote the row (stamped by a trigger). A member who deletes
--   their account leaves their edits in the ledger, with user_id set to null.
-- * Reading needs membership; writing needs the owner or editor role and an active
--   subscription of the ledger's owner.
-- * Every existing user gets a ledger of their own holding the rows they had.
-- * own_ledger(): the id of the signed-in user's own ledger, made the first time it's asked for.
--
-- Builds of the app from before this migration upsert with `on_conflict=user_id,id` and don't
-- send `ledger_id`, so their uploads fail; update the app on every device when deploying.
--
-- Safe to re-run, also after the earlier migrations have been re-run: constraints and the
-- settings key index keep the names the earlier migrations look for, so those find them and
-- leave them alone.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. Ledgers and their members
-- -----------------------------------------------------------------------------
create table if not exists public.ledgers (
  id          uuid primary key default gen_random_uuid(),
  owner_id    uuid not null references auth.users (id) on delete cascade,
  name        text not null default 'My ledger' check (length(name) between 1 and 100),
  created_at  timestamptz not null default now()
);

create index if not exists ix_ledgers_owner on public.ledgers (owner_id);

create table if not exists public.ledger_members (
  ledger_id   uuid not null references public.ledgers (id) on delete cascade,
  user_id     uuid not null references auth.users (id) on delete cascade,
  role        text not null check (role in ('owner', 'editor', 'viewer')),
  created_at  timestamptz not null default now(),
  primary key (ledger_id, user_id)
);

create index if not exists ix_ledger_members_user on public.ledger_members (user_id);
create unique index if not exists ledger_members_one_owner_uq on public.ledger_members (ledger_id) where role = 'owner';

-- The owner is always a member.
create or replace function public.add_ledger_owner()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.ledger_members (ledger_id, user_id, role)
  values (new.id, new.owner_id, 'owner')
  on conflict (ledger_id, user_id) do update set role = 'owner';
  return new;
end;
$$;

revoke all on function public.add_ledger_owner() from public, anon, authenticated;

drop trigger if exists trg_ledgers_add_owner on public.ledgers;
create trigger trg_ledgers_add_owner
  after insert on public.ledgers
  for each row execute function public.add_ledger_owner();

-- Ledgers the signed-in user is a member of, and those they may upload to. Security definer, so
-- policies can use them without granting access to the membership tables. Policies call them as
-- `ledger_id in (select …)`, which Postgres evaluates once per query.
create or replace function public.member_ledger_ids()
returns setof uuid
language sql
stable
security definer
set search_path = ''
as $$
  select m.ledger_id from public.ledger_members m where m.user_id = (select auth.uid());
$$;

create or replace function public.writable_ledger_ids()
returns setof uuid
language sql
stable
security definer
set search_path = ''
as $$
  select m.ledger_id
  from public.ledger_members m
  join public.ledgers l on l.id = m.ledger_id
  join public.cloud_subscriptions s on s.user_id = l.owner_id
  where m.user_id = (select auth.uid())
    and m.role in ('owner', 'editor')
    and (s.expires_at is null or s.expires_at > now());
$$;

revoke all on function public.member_ledger_ids() from public, anon;
revoke all on function public.writable_ledger_ids() from public, anon;
grant execute on function public.member_ledger_ids() to authenticated;
grant execute on function public.writable_ledger_ids() to authenticated;

-- Members see their ledgers and who else is in them. Changes go through functions.
alter table public.ledgers enable row level security;
alter table public.ledger_members enable row level security;

drop policy if exists ledgers_select_member on public.ledgers;
create policy ledgers_select_member on public.ledgers
  for select to authenticated using (id in (select public.member_ledger_ids()));

drop policy if exists ledger_members_select_member on public.ledger_members;
create policy ledger_members_select_member on public.ledger_members
  for select to authenticated using (ledger_id in (select public.member_ledger_ids()));

-- -----------------------------------------------------------------------------
-- 2. A ledger for every user, holding the rows they had
-- -----------------------------------------------------------------------------
insert into public.ledgers (owner_id)
select u.id from auth.users u
where not exists (select 1 from public.ledgers l where l.owner_id = u.id);

do $$
declare
  t text;
begin
  foreach t in array array['accounts', 'transactions', 'transaction_entries', 'assets', 'invoices', 'user_settings']
  loop
    continue when to_regclass('public.' || t) is null;
    execute format('alter table public.%I add column if not exists ledger_id uuid references public.ledgers (id) on delete cascade', t);
    -- Only rows from before this migration lack a ledger, and their users own exactly one.
    execute format(
      'update public.%I r set ledger_id = l.id from public.ledgers l where r.ledger_id is null and l.owner_id = r.user_id', t);
    execute format('alter table public.%I alter column ledger_id set not null', t);
  end loop;
end;
$$;

-- -----------------------------------------------------------------------------
-- 3. Keys per ledger: (user_id, id) → (ledger_id, id)
-- -----------------------------------------------------------------------------
do $$
declare
  t text;
  c record;
begin
  -- References between the synced tables depend on the primary keys; they are recreated below.
  for c in
    select con.conname, rel.relname
    from pg_constraint con
    join pg_class rel on rel.oid = con.conrelid
    join pg_namespace ns on ns.oid = rel.relnamespace
    join pg_class ref on ref.oid = con.confrelid
    where con.contype = 'f'
      and ns.nspname = 'public'
      and ref.relname in ('accounts', 'transactions', 'transaction_entries', 'assets', 'invoices', 'user_settings')
      and not exists (
        select 1 from pg_attribute a
        where a.attrelid = con.conrelid and a.attnum = any (con.conkey) and a.attname = 'ledger_id')
  loop
    execute format('alter table public.%I drop constraint %I', c.relname, c.conname);
  end loop;

  foreach t in array array['accounts', 'transactions', 'transaction_entries', 'assets', 'invoices', 'user_settings']
  loop
    continue when to_regclass('public.' || t) is null;
    select con.conname into c
    from pg_constraint con
    where con.conrelid = format('public.%I', t)::regclass
      and con.contype = 'p'
      and not exists (
        select 1 from pg_attribute a
        where a.attrelid = con.conrelid and a.attnum = any (con.conkey) and a.attname = 'ledger_id');
    if found then
      execute format('alter table public.%I drop constraint %I', t, c.conname);
      execute format('alter table public.%I add constraint %I primary key (ledger_id, id)', t, t || '_pkey');
    end if;
  end loop;
end;
$$;

-- References within one ledger, under the names 20261006000000_per_user_row_ids.sql gave them.
do $$
begin
  if to_regclass('public.transaction_entries') is not null then
    begin
      alter table public.transaction_entries add constraint transaction_entries_transaction_fkey
        foreign key (ledger_id, transaction_id) references public.transactions (ledger_id, id) on delete cascade;
    exception when duplicate_object then null;
    end;
    begin
      alter table public.transaction_entries add constraint transaction_entries_account_fkey
        foreign key (ledger_id, account_id) references public.accounts (ledger_id, id);
    exception when duplicate_object then null;
    end;
  end if;
end;
$$;

do $$
begin
  alter table public.assets add constraint assets_debit_account_fkey
    foreign key (ledger_id, debit_account_id) references public.accounts (ledger_id, id);
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter table public.assets add constraint assets_credit_account_fkey
    foreign key (ledger_id, credit_account_id) references public.accounts (ledger_id, id);
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter table public.invoices add constraint invoices_booking_transaction_fkey
    foreign key (ledger_id, booking_transaction_id) references public.transactions (ledger_id, id);
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter table public.invoices add constraint invoices_outstanding_account_fkey
    foreign key (ledger_id, outstanding_account_id) references public.accounts (ledger_id, id);
exception when duplicate_object then null;
end;
$$;

-- Nullable columns: a NULL reference is not checked (MATCH SIMPLE), as before.
do $$
begin
  alter table public.invoices add constraint invoices_payment_transaction_fkey
    foreign key (ledger_id, payment_transaction_id) references public.transactions (ledger_id, id);
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter table public.invoices add constraint invoices_paid_from_account_fkey
    foreign key (ledger_id, paid_from_account_id) references public.accounts (ledger_id, id);
exception when duplicate_object then null;
end;
$$;

-- One row per setting key and ledger (settings ids are derived from the key). The index keeps
-- its old name so that re-running 20261006000001_align_hand_made_schema.sql finds it instead of
-- creating a per-user index, which a user writing one key in two ledgers would violate.
do $$
begin
  if exists (
    select 1 from pg_index i
    join pg_class c on c.oid = i.indexrelid
    join pg_namespace ns on ns.oid = c.relnamespace
    where ns.nspname = 'public' and c.relname = 'user_settings_user_key_uq'
      and pg_get_indexdef(i.indexrelid) not like '%(ledger_id, key)%'
  ) then
    drop index public.user_settings_user_key_uq;
  end if;
end;
$$;
create unique index if not exists user_settings_user_key_uq on public.user_settings (ledger_id, key);

-- -----------------------------------------------------------------------------
-- 4. user_id: who last wrote the row
-- -----------------------------------------------------------------------------
-- The signed-in user, whatever the client sent. Only edits count: every edit carries a new
-- modified_at, while clearing user_id for a deleted account (the set null below) doesn't.
-- Writes without a user (service role) keep the value they carry.
create or replace function public.set_modified_by()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' or new.modified_at is distinct from old.modified_at then
    new.user_id := coalesce((select auth.uid()), new.user_id);
  end if;
  return new;
end;
$$;

do $$
declare
  t text;
  c record;
begin
  foreach t in array array['accounts', 'transactions', 'transaction_entries', 'assets', 'invoices', 'user_settings']
  loop
    continue when to_regclass('public.' || t) is null;
    execute format('alter table public.%I alter column user_id drop not null', t);

    -- A member's account going away must not take their edits out of a shared ledger.
    for c in
      select con.conname
      from pg_constraint con
      join pg_attribute a on a.attrelid = con.conrelid and a.attnum = any (con.conkey)
      where con.conrelid = format('public.%I', t)::regclass
        and con.contype = 'f'
        and con.confrelid = 'auth.users'::regclass
        and a.attname = 'user_id'
        and con.confdeltype <> 'n'
    loop
      execute format('alter table public.%I drop constraint %I', t, c.conname);
    end loop;
    if not exists (
      select 1 from pg_constraint con
      where con.conrelid = format('public.%I', t)::regclass and con.contype = 'f' and con.confrelid = 'auth.users'::regclass
    ) then
      execute format(
        'alter table public.%I add constraint %I foreign key (user_id) references auth.users (id) on delete set null',
        t, t || '_user_id_fkey');
    end if;

    -- Not for the unused transaction_entries: nothing writes it any more.
    continue when t = 'transaction_entries';
    execute format('drop trigger if exists %I on public.%I', 'trg_' || t || '_modified_by', t);
    execute format(
      'create trigger %I before insert or update on public.%I for each row execute function public.set_modified_by()',
      'trg_' || t || '_modified_by', t);
  end loop;
end;
$$;

-- -----------------------------------------------------------------------------
-- 5. Indexes for the pull (updated_at) and for replacing a ledger (modified_at), per ledger
-- -----------------------------------------------------------------------------
create index if not exists ix_accounts_ledger_updated on public.accounts (ledger_id, updated_at);
create index if not exists ix_transactions_ledger_updated on public.transactions (ledger_id, updated_at);
create index if not exists ix_assets_ledger_updated on public.assets (ledger_id, updated_at);
create index if not exists ix_invoices_ledger_updated on public.invoices (ledger_id, updated_at);
create index if not exists ix_user_settings_ledger_updated on public.user_settings (ledger_id, updated_at);

create index if not exists ix_accounts_ledger_modified on public.accounts (ledger_id, modified_at);
create index if not exists ix_transactions_ledger_modified on public.transactions (ledger_id, modified_at);
create index if not exists ix_assets_ledger_modified on public.assets (ledger_id, modified_at);
create index if not exists ix_invoices_ledger_modified on public.invoices (ledger_id, modified_at);
create index if not exists ix_user_settings_ledger_modified on public.user_settings (ledger_id, modified_at);

-- The ix_*_user_updated indexes stay: deleting a user looks up their rows to clear user_id.
drop index if exists public.ix_accounts_user_modified;
drop index if exists public.ix_transactions_user_modified;
drop index if exists public.ix_assets_user_modified;
drop index if exists public.ix_invoices_user_modified;
drop index if exists public.ix_user_settings_user_modified;

-- -----------------------------------------------------------------------------
-- 6. Row-level security by membership
-- -----------------------------------------------------------------------------
-- Reading needs membership. Writing needs the owner or editor role and the owner's cloud
-- access; without it PostgREST answers 403 (42501), as before.
do $$
declare
  t text;
begin
  foreach t in array array['accounts', 'transactions', 'transaction_entries', 'assets', 'invoices', 'user_settings']
  loop
    continue when to_regclass('public.' || t) is null;
    execute format('drop policy if exists %I on public.%I', t || '_select_own', t);
    execute format('drop policy if exists %I on public.%I', t || '_insert_own', t);
    execute format('drop policy if exists %I on public.%I', t || '_update_own', t);

    execute format('drop policy if exists %I on public.%I', t || '_select_member', t);
    execute format(
      'create policy %I on public.%I for select to authenticated using (ledger_id in (select public.member_ledger_ids()))',
      t || '_select_member', t);

    execute format('drop policy if exists %I on public.%I', t || '_insert_member', t);
    execute format(
      'create policy %I on public.%I for insert to authenticated with check (ledger_id in (select public.writable_ledger_ids()))',
      t || '_insert_member', t);

    execute format('drop policy if exists %I on public.%I', t || '_update_member', t);
    execute format(
      'create policy %I on public.%I for update to authenticated using (ledger_id in (select public.member_ledger_ids())) with check (ledger_id in (select public.writable_ledger_ids()))',
      t || '_update_member', t);
  end loop;
end;
$$;

-- -----------------------------------------------------------------------------
-- 7. The app's entry point: `POST /rest/v1/rpc/own_ledger`
-- -----------------------------------------------------------------------------
-- The signed-in user's own ledger (the oldest they own), made the first time it's asked for.
-- The lock keeps two devices signing in at once from making two.
create or replace function public.own_ledger()
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  me uuid := (select auth.uid());
  result uuid;
begin
  if me is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  perform pg_advisory_xact_lock(hashtextextended('own_ledger:' || me::text, 0));
  select l.id into result from public.ledgers l where l.owner_id = me order by l.created_at, l.id limit 1;
  if result is null then
    insert into public.ledgers (owner_id) values (me) returning id into result;
  end if;
  return result;
end;
$$;

revoke all on function public.own_ledger() from public, anon;
grant execute on function public.own_ledger() to authenticated;
