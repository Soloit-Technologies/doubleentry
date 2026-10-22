-- =============================================================================
-- Migration: email and password sign-in only for accounts we create by hand
--
-- Accounts are created with Sign in with Google. A few people need a password instead: internal
-- users without Google, and app reviewers. The email provider has to be on for them to sign in, and
-- that also opens `POST /auth/v1/signup` (and sign-up by emailed code) to anyone with the anon key.
-- This before_user_created hook closes it again: every new email account is refused. Google
-- sign-ups are never affected.
--
-- Users created with the admin API, which is what the dashboard's Authentication → Add user does,
-- don't pass through the hook, so that is how a password account is made (see supabase/README.md).
--
-- Safe to re-run.
-- =============================================================================

create or replace function public.hook_before_user_created(event jsonb)
returns jsonb
language plpgsql
set search_path = ''
as $$
begin
  if event->'user'->'app_metadata'->>'provider' = 'email' then
    return jsonb_build_object('error', jsonb_build_object(
      'message', 'New accounts are created with Sign in with Google.',
      'http_code', 403));
  end if;
  return '{}'::jsonb;
end;
$$;

grant execute on function public.hook_before_user_created(jsonb) to supabase_auth_admin;
revoke execute on function public.hook_before_user_created(jsonb) from authenticated, anon, public;
