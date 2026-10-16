-- =============================================================================
-- Migration: sync error reports
--
-- * sync_error_reports: unexpected sync failures the app sends in, so problems show up without
--   users having to report them. A report holds the kind of error, its technical message without
--   row content, where in the sync code it happened, the app version and how many changes were
--   waiting; never ledger data. Offline, an ended session and a missing subscription aren't
--   reported. Read them with supabase/queries/sync_errors.sql.
-- * Users can only add reports, as themselves, at most 50 a day; nobody but the service role and
--   the SQL editor reads them. They go with the account, and are deleted after 90 days (pg_cron).
--
-- Safe to re-run.
-- =============================================================================

create table if not exists public.sync_error_reports (
  id               bigint generated always as identity primary key,
  user_id          uuid not null default auth.uid() references auth.users (id) on delete cascade,
  created_at       timestamptz not null default now(),
  app_version      integer not null,
  kind             text not null check (length(kind) <= 200),
  detail           text check (length(detail) <= 2000),
  pending_changes  integer
);

create index if not exists ix_sync_error_reports_created on public.sync_error_reports (created_at);
create index if not exists ix_sync_error_reports_user_created on public.sync_error_reports (user_id, created_at);

alter table public.sync_error_reports enable row level security;
revoke all on table public.sync_error_reports from anon, authenticated;
grant insert (app_version, kind, detail, pending_changes) on table public.sync_error_reports to authenticated;

drop policy if exists sync_error_reports_insert_own on public.sync_error_reports;
create policy sync_error_reports_insert_own on public.sync_error_reports
  for insert to authenticated with check (user_id = (select auth.uid()));

-- A device stuck in a failing loop mustn't fill the table: beyond 50 a day, reports are dropped.
create or replace function public.limit_sync_error_reports()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if (select count(*) from public.sync_error_reports r
      where r.user_id = new.user_id and r.created_at > now() - interval '1 day') >= 50 then
    return null;
  end if;
  return new;
end;
$$;

revoke all on function public.limit_sync_error_reports() from public, anon, authenticated;

drop trigger if exists trg_sync_error_reports_limit on public.sync_error_reports;
create trigger trg_sync_error_reports_limit
  before insert on public.sync_error_reports
  for each row execute function public.limit_sync_error_reports();

-- Reports are kept 90 days (the privacy policy says so). Scheduling by name replaces the job.
create extension if not exists pg_cron;
select cron.schedule('purge-sync-error-reports', '17 3 * * *',
  $$delete from public.sync_error_reports where created_at < now() - interval '90 days'$$);
