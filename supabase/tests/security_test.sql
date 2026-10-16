-- =============================================================================
-- Row-level security and the rules users mustn't get around, run with `supabase test db`
-- (pgTAP). The app's contract tests (app/src/test/.../SupabaseContractTest.kt) cover what the
-- app does; these cover what a user could try with their own token and hand-written requests.
--
-- Everything runs in one transaction that is rolled back.
-- =============================================================================

begin;
create extension if not exists pgtap with schema extensions;
set search_path = public, extensions;

select plan(31);

-- -----------------------------------------------------------------------------
-- Guards for whatever is added later
-- -----------------------------------------------------------------------------
select is(
  (select array_agg(c.relname::text order by c.relname) from pg_class c join pg_namespace n on n.oid = c.relnamespace
   where n.nspname = 'public' and c.relkind = 'r' and not c.relrowsecurity),
  null,
  'every public table has row-level security');

select is(
  (select array_agg(p.proname::text order by p.proname) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.prosecdef and not coalesce(array_to_string(p.proconfig, ',') like '%search_path=%', false)),
  null,
  'every security definer function pins its search_path');

select is(
  (select array_agg(p.proname::text order by p.proname) from pg_proc p join pg_namespace n on n.oid = p.pronamespace
   where n.nspname = 'public' and p.prorettype <> 'trigger'::regtype and has_function_privilege('anon', p.oid, 'execute')),
  array['sync_control'],
  'signed out, only sync_control() can be called');

-- -----------------------------------------------------------------------------
-- A ledger with an owner (who has cloud access), an editor, a viewer, and a stranger
-- -----------------------------------------------------------------------------
insert into auth.users (id, email, aud, role) values
  ('11111111-1111-4111-8111-111111111111', 'owner@example.com', 'authenticated', 'authenticated'),
  ('22222222-2222-4222-8222-222222222222', 'editor@example.com', 'authenticated', 'authenticated'),
  ('33333333-3333-4333-8333-333333333333', 'viewer@example.com', 'authenticated', 'authenticated'),
  ('44444444-4444-4444-8444-444444444444', 'stranger@example.com', 'authenticated', 'authenticated');
insert into public.cloud_subscriptions (user_id, source, state) values
  ('11111111-1111-4111-8111-111111111111', 'complimentary', 'active'),
  ('44444444-4444-4444-8444-444444444444', 'complimentary', 'active');
insert into public.ledgers (id, owner_id) values ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111111');
insert into public.ledger_members (ledger_id, user_id, role) values
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '22222222-2222-4222-8222-222222222222', 'editor'),
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '33333333-3333-4333-8333-333333333333', 'viewer');
insert into public.accounts (ledger_id, id, user_id, number, name, type, modified_at) values
  ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb', '11111111-1111-4111-8111-111111111111', 1910, 'Bank', 0, '2026-09-01T00:00:00Z');
insert into public.ledger_invites (code_hash, ledger_id, role, created_by)
values (public.invite_code_hash('ABCD-EFGH-JKMN-PQRS'), 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'editor', '11111111-1111-4111-8111-111111111111');

-- -----------------------------------------------------------------------------
-- Signed out
-- -----------------------------------------------------------------------------
set local role anon;
select is((select count(*) from public.accounts), 0::bigint, 'signed out, no rows can be read');
select throws_ok($$ select public.own_ledger() $$, '42501', null, 'signed out, own_ledger() is refused');
select is((select paused from public.sync_control()), false, 'signed out, the sync switch can be read');

-- -----------------------------------------------------------------------------
-- The stranger
-- -----------------------------------------------------------------------------
reset role;
select set_config('request.jwt.claims', '{"sub": "44444444-4444-4444-8444-444444444444", "role": "authenticated"}', true);
set local role authenticated;

select is((select count(*) from public.accounts where ledger_id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'), 0::bigint,
  'a stranger reads nothing of the ledger');
select is((select count(*) from public.ledger_members where ledger_id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'), 0::bigint,
  'a stranger doesn''t see who is in it');
select throws_ok(
  $$ insert into public.accounts (ledger_id, id, number, name, type, modified_at)
     values ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', gen_random_uuid(), 1920, 'Mine now', 0, now()) $$,
  '42501', null, 'a stranger can''t write to the ledger');
select throws_ok(
  $$ insert into public.ledger_members (ledger_id, user_id, role)
     values ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '44444444-4444-4444-8444-444444444444', 'owner') $$,
  '42501', null, 'a stranger can''t make themselves a member');
select throws_ok(
  $$ insert into public.ledgers (owner_id) values ('11111111-1111-4111-8111-111111111111') $$,
  '42501', null, 'nobody makes ledgers by hand (own_ledger() does)');
select is((select count(*) from public.ledger_invites), 0::bigint, 'invite codes can''t be read, not even their hashes');
select throws_ok($$ select public.list_members('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa') $$, 'P0001', 'not_member',
  'a stranger can''t list the members');
select throws_ok($$ select public.create_invite('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'editor') $$, 'P0001', 'not_owner',
  'a stranger can''t invite people');

-- Granting oneself cloud access.
update public.cloud_subscriptions set expires_at = now() + interval '100 years'
where user_id = '44444444-4444-4444-8444-444444444444';
select is((select expires_at from public.cloud_subscriptions where user_id = '44444444-4444-4444-8444-444444444444'), null,
  'cloud access can''t be changed by its user');
select throws_ok(
  $$ insert into public.cloud_subscriptions (user_id, source, state)
     values ('22222222-2222-4222-8222-222222222222', 'complimentary', 'active') $$,
  '42501', null, 'cloud access can''t be granted by users');

-- Sync error reports: only adding one's own.
select lives_ok(
  $$ insert into public.sync_error_reports (app_version, kind, detail) values (13, 'IllegalStateException', 'test') $$,
  'a user can report a sync error');
select throws_ok(
  $$ insert into public.sync_error_reports (user_id, app_version, kind) values ('11111111-1111-4111-8111-111111111111', 13, 'Spoofed') $$,
  '42501', null, 'reports can''t be filed for someone else');
select throws_ok($$ select count(*) from public.sync_error_reports $$, '42501', null, 'reports can''t be read back');
select throws_ok($$ select count(*) from public.service_control $$, '42501', null, 'the switch table itself is closed');

-- -----------------------------------------------------------------------------
-- The viewer
-- -----------------------------------------------------------------------------
reset role;
select set_config('request.jwt.claims', '{"sub": "33333333-3333-4333-8333-333333333333", "role": "authenticated"}', true);
set local role authenticated;

select is((select count(*) from public.accounts where ledger_id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'), 1::bigint, 'a viewer reads the ledger');
select throws_ok(
  $$ update public.accounts set name = 'Renamed', modified_at = now() where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' $$,
  '42501', null, 'a viewer can''t edit it');
update public.ledger_members set role = 'editor' where user_id = '33333333-3333-4333-8333-333333333333';
select is((select role from public.ledger_members where user_id = '33333333-3333-4333-8333-333333333333'), 'viewer',
  'a viewer can''t promote themselves');

-- -----------------------------------------------------------------------------
-- The editor
-- -----------------------------------------------------------------------------
reset role;
select set_config('request.jwt.claims', '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}', true);
set local role authenticated;

update public.accounts set name = 'Bank account', modified_at = '2026-09-02T00:00:00Z', user_id = '11111111-1111-4111-8111-111111111111'
where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
select is((select user_id from public.accounts where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'), '22222222-2222-4222-8222-222222222222'::uuid,
  'an edit is attributed to whoever made it, whatever the request says');

update public.accounts set name = 'Stale', modified_at = '2026-09-01T12:00:00Z' where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
select is((select name from public.accounts where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'), 'Bank account', 'an older edit doesn''t replace a newer one');

update public.accounts set name = 'Same time', modified_at = '2026-09-02T00:00:00Z' where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
select is((select name from public.accounts where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'), 'Same time',
  'an edit as new as the stored one is accepted (a resent upload)');

select throws_ok(
  $$ update public.accounts set ledger_id = (select public.own_ledger()), modified_at = now() where id = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' $$,
  '42501', null, 'a row can''t be moved to a ledger the editor can''t write (no cloud access of their own)');

select throws_ok($$ select public.remove_member('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', '33333333-3333-4333-8333-333333333333') $$,
  'P0001', 'not_owner', 'an editor can''t remove other members');

-- The owner's subscription ends: nobody can upload any more, everyone can still read.
reset role;
update public.cloud_subscriptions set expires_at = now() - interval '1 minute' where user_id = '11111111-1111-4111-8111-111111111111';
select set_config('request.jwt.claims', '{"sub": "22222222-2222-4222-8222-222222222222", "role": "authenticated"}', true);
set local role authenticated;
select throws_ok(
  $$ insert into public.accounts (ledger_id, id, number, name, type, modified_at)
     values ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', gen_random_uuid(), 1930, 'Savings', 0, now()) $$,
  '42501', null, 'without the owner''s subscription an editor can''t upload');
select is((select count(*) from public.accounts where ledger_id = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'), 1::bigint,
  'but still reads the ledger');

-- -----------------------------------------------------------------------------
-- Error reports are capped at 50 a day per user
-- -----------------------------------------------------------------------------
insert into public.sync_error_reports (app_version, kind) select 13, 'Loop' from generate_series(1, 60);
reset role;
select is((select count(*) from public.sync_error_reports where user_id = '22222222-2222-4222-8222-222222222222'), 50::bigint,
  'a device stuck in a loop files at most 50 reports a day');

select * from finish();
rollback;
