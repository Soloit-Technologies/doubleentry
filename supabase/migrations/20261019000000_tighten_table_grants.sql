-- =============================================================================
-- Migration: table privileges no wider than the policies
--
-- Supabase grants anon and authenticated every privilege on new tables and leaves the rest to
-- row-level security. RLS already refuses everything below; taking the privileges away as well
-- means a policy added carelessly later can't open it up.
--
-- * anon (signed out): nothing on any table. sync_control() is a function and keeps working.
-- * authenticated: no delete, truncate, references or trigger anywhere (rows are tombstoned,
--   never deleted); only select on ledgers, ledger_members and cloud_subscriptions (functions and
--   the service role change them); nothing on ledger_invites (only the invite functions use it).
-- * Tables created later in public start the same way: nothing for anon, no delete or truncate
--   for authenticated, unless a migration grants it.
--
-- Safe to re-run.
-- =============================================================================

revoke all on all tables in schema public from anon;
revoke all on all sequences in schema public from anon;
revoke delete, truncate, references, trigger on all tables in schema public from authenticated;

revoke insert, update on public.ledgers, public.ledger_members, public.cloud_subscriptions from authenticated;
revoke all on public.ledger_invites from authenticated;

alter default privileges for role postgres in schema public revoke all on tables from anon;
alter default privileges for role postgres in schema public revoke all on sequences from anon;
alter default privileges for role postgres in schema public
  revoke delete, truncate, references, trigger on tables from authenticated;
