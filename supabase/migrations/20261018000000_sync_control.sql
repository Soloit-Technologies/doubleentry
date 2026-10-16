-- =============================================================================
-- Migration: a switch to stop sync from the server
--
-- * service_control: one row. `sync_paused` stops sync in every app; `min_version_code` stops it
--   in builds older than that versionCode (e.g. one with a sync bug), which then ask the user to
--   update. `message` replaces the app's text for either. Changes wait on the devices and upload
--   when sync runs again; nothing is lost.
-- * sync_control(): what the app reads before each sync, signed in or not.
--
-- Flip it in the SQL editor, see supabase/README.md (Stopping sync).
--
-- Safe to re-run.
-- =============================================================================

create table if not exists public.service_control (
  id                boolean primary key default true check (id),
  sync_paused       boolean not null default false,
  min_version_code  integer not null default 0 check (min_version_code >= 0),
  message           text check (length(message) <= 500)
);

insert into public.service_control (id) values (true) on conflict (id) do nothing;

-- Only the function below reads it; only the service role and the SQL editor change it.
alter table public.service_control enable row level security;
revoke all on table public.service_control from anon, authenticated;

create or replace function public.sync_control()
returns table (paused boolean, min_version_code integer, message text)
language sql
stable
security definer
set search_path = ''
as $$
  select c.sync_paused, c.min_version_code, c.message from public.service_control c where c.id;
$$;

revoke all on function public.sync_control() from public;
grant execute on function public.sync_control() to anon, authenticated;
