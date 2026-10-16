-- =============================================================================
-- Migration: inviting people to a ledger
--
-- * ledger_invites: single-use codes, valid for 7 days, that make whoever enters one a member of
--   a ledger. Only a hash of each code is stored.
-- * Functions the app calls (`POST /rest/v1/rpc/<name>`), all for the signed-in user:
--     create_invite(ledger, role)  the owner makes a code to send to someone
--     invite_details(code)         what a code is for, before accepting it
--     accept_invite(code)          joins the ledger; returns its id
--     list_ledgers()               the user's ledgers
--     list_members(ledger)         the members of one of them
--     remove_member(ledger, user)  the owner removes someone, or a member leaves
--     rename_ledger(ledger, name)  the owner renames it
--     ledger_access(ledger)        the user's role and whether they may upload to it now
--   Refusals raise P0001 with a machine-readable message (invite_not_found, invite_expired,
--   invite_used, not_owner, owner_cannot_leave, not_member); PostgREST answers 400 with it.
--
-- Safe to re-run.
-- =============================================================================

create table if not exists public.ledger_invites (
  -- sha256 of the code without separators, upper case.
  code_hash    bytea primary key,
  ledger_id    uuid not null references public.ledgers (id) on delete cascade,
  role         text not null check (role in ('editor', 'viewer')),
  created_by   uuid not null references auth.users (id) on delete cascade,
  created_at   timestamptz not null default now(),
  expires_at   timestamptz not null default now() + interval '7 days',
  accepted_by  uuid references auth.users (id) on delete set null,
  accepted_at  timestamptz
);

create index if not exists ix_ledger_invites_ledger on public.ledger_invites (ledger_id);
create index if not exists ix_ledger_invites_created_by on public.ledger_invites (created_by);
create index if not exists ix_ledger_invites_accepted_by on public.ledger_invites (accepted_by);

-- Only the functions below read or write invites.
alter table public.ledger_invites enable row level security;

-- -----------------------------------------------------------------------------
-- Helpers (not callable by clients)
-- -----------------------------------------------------------------------------
-- A code as people type it: case, spaces and dashes don't matter, and the letters that look like
-- digits read as those digits (Crockford base 32).
create or replace function public.invite_code_hash(code text)
returns bytea
language sql
immutable
set search_path = ''
as $$
  select sha256(convert_to(translate(upper(regexp_replace(coalesce(code, ''), '[\s-]', '', 'g')), 'OIL', '011'), 'UTF8'));
$$;

create or replace function public.require_ledger_owner(ledger uuid)
returns void
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if not exists (
    select 1 from public.ledger_members m
    where m.ledger_id = ledger and m.user_id = (select auth.uid()) and m.role = 'owner'
  ) then
    raise exception 'not_owner' using errcode = 'P0001';
  end if;
end;
$$;

-- The invite behind a code, if it can still be used; raises otherwise.
create or replace function public.usable_invite(code text)
returns public.ledger_invites
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  invite public.ledger_invites;
begin
  select * into invite from public.ledger_invites i where i.code_hash = public.invite_code_hash(code);
  if not found then
    raise exception 'invite_not_found' using errcode = 'P0001';
  end if;
  if invite.accepted_at is not null then
    raise exception 'invite_used' using errcode = 'P0001';
  end if;
  if invite.expires_at <= now() then
    raise exception 'invite_expired' using errcode = 'P0001';
  end if;
  return invite;
end;
$$;

revoke all on function public.invite_code_hash(text) from public, anon, authenticated;
revoke all on function public.require_ledger_owner(uuid) from public, anon, authenticated;
revoke all on function public.usable_invite(text) from public, anon, authenticated;

-- -----------------------------------------------------------------------------
-- Inviting
-- -----------------------------------------------------------------------------
-- 16 characters of Crockford base 32 (about 78 random bits), shown as XXXX-XXXX-XXXX-XXXX.
create or replace function public.create_invite(ledger uuid, role text default 'editor')
returns table (code text, expires_at timestamptz)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  alphabet constant text := '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
  bytes bytea := uuid_send(gen_random_uuid());
  plain text := '';
  invite public.ledger_invites;
begin
  perform public.require_ledger_owner(ledger);
  if role not in ('editor', 'viewer') then
    raise exception 'invalid_role' using errcode = 'P0001';
  end if;
  for i in 0..15 loop
    plain := plain || substr(alphabet, (get_byte(bytes, i) & 31) + 1, 1);
  end loop;
  insert into public.ledger_invites (code_hash, ledger_id, role, created_by)
  values (public.invite_code_hash(plain), ledger, role, (select auth.uid()))
  returning * into invite;
  return query select substr(plain, 1, 4) || '-' || substr(plain, 5, 4) || '-' || substr(plain, 9, 4) || '-' || substr(plain, 13, 4),
                      invite.expires_at;
end;
$$;

create or replace function public.invite_details(code text)
returns table (ledger_id uuid, ledger_name text, owner_email text, role text, already_member boolean)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  invite public.ledger_invites := public.usable_invite(code);
begin
  return query
    select l.id, l.name, u.email::text, invite.role,
           exists (select 1 from public.ledger_members m where m.ledger_id = l.id and m.user_id = (select auth.uid()))
    from public.ledgers l
    join auth.users u on u.id = l.owner_id
    where l.id = invite.ledger_id;
end;
$$;

-- Joining a ledger one is already in leaves the invite for someone else and keeps one's role.
create or replace function public.accept_invite(code text)
returns uuid
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  me uuid := (select auth.uid());
  invite public.ledger_invites := public.usable_invite(code);
begin
  if me is null then
    raise exception 'Not signed in' using errcode = '42501';
  end if;
  if exists (select 1 from public.ledger_members m where m.ledger_id = invite.ledger_id and m.user_id = me) then
    return invite.ledger_id;
  end if;
  -- Two people entering the same code at once: only one gets it.
  update public.ledger_invites i set accepted_by = me, accepted_at = now()
  where i.code_hash = invite.code_hash and i.accepted_at is null;
  if not found then
    raise exception 'invite_used' using errcode = 'P0001';
  end if;
  insert into public.ledger_members (ledger_id, user_id, role) values (invite.ledger_id, me, invite.role);
  return invite.ledger_id;
end;
$$;

-- -----------------------------------------------------------------------------
-- Ledgers and members
-- -----------------------------------------------------------------------------
create or replace function public.list_ledgers()
returns table (id uuid, name text, role text, owner_email text, member_count integer, created_at timestamptz)
language sql
stable
security definer
set search_path = ''
as $$
  select l.id, l.name, m.role, u.email::text,
         (select count(*)::integer from public.ledger_members x where x.ledger_id = l.id),
         l.created_at
  from public.ledger_members m
  join public.ledgers l on l.id = m.ledger_id
  join auth.users u on u.id = l.owner_id
  where m.user_id = (select auth.uid())
  order by l.created_at, l.id;
$$;

create or replace function public.list_members(ledger uuid)
returns table (user_id uuid, email text, role text, joined_at timestamptz)
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if not exists (select 1 from public.ledger_members m where m.ledger_id = ledger and m.user_id = (select auth.uid())) then
    raise exception 'not_member' using errcode = 'P0001';
  end if;
  return query
    select m.user_id, u.email::text, m.role, m.created_at
    from public.ledger_members m
    join auth.users u on u.id = m.user_id
    where m.ledger_id = ledger
    order by m.role = 'owner' desc, m.created_at, u.email;
end;
$$;

-- The owner removes anyone else; a member removes themselves (leaves). The owner can't leave.
-- Their edits stay in the ledger.
create or replace function public.remove_member(ledger uuid, member uuid)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  me uuid := (select auth.uid());
begin
  if member = me then
    if exists (select 1 from public.ledgers l where l.id = ledger and l.owner_id = me) then
      raise exception 'owner_cannot_leave' using errcode = 'P0001';
    end if;
  else
    perform public.require_ledger_owner(ledger);
  end if;
  delete from public.ledger_members m where m.ledger_id = ledger and m.user_id = member and m.role <> 'owner';
end;
$$;

create or replace function public.rename_ledger(ledger uuid, name text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  perform public.require_ledger_owner(ledger);
  update public.ledgers l set name = btrim(rename_ledger.name) where l.id = ledger;
end;
$$;

-- Always one row: role is null when the user isn't (or no longer is) a member.
create or replace function public.ledger_access(ledger uuid)
returns table (role text, writable boolean, owner_email text)
language sql
stable
security definer
set search_path = ''
as $$
  select m.role,
         coalesce(ledger in (select public.writable_ledger_ids()), false),
         (select u.email::text from public.ledgers l join auth.users u on u.id = l.owner_id
          where l.id = ledger and m.role is not null)
  from (select 1) as one
  left join public.ledger_members m on m.ledger_id = ledger and m.user_id = (select auth.uid());
$$;

do $$
declare
  f text;
begin
  foreach f in array array[
    'create_invite(uuid, text)', 'invite_details(text)', 'accept_invite(text)', 'list_ledgers()',
    'list_members(uuid)', 'remove_member(uuid, uuid)', 'rename_ledger(uuid, text)', 'ledger_access(uuid)'
  ]
  loop
    execute format('revoke all on function public.%s from public, anon', f);
    execute format('grant execute on function public.%s to authenticated', f);
  end loop;
end;
$$;
