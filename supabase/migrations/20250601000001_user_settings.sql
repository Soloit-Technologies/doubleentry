-- =============================================================================
-- Migration: user_settings
-- Adds the public.user_settings table with the columns matching the app's
-- UserSettingRow DTO (SupabaseAutoSyncService.cs), an updated_at trigger,
-- RLS policies scoped to the signed-in user, and Realtime support.
--
-- The row Id is derived client-side as SHA256(key) so two devices that create
-- the same setting independently will converge via on_conflict=id upsert.
-- Note: this scheme assumes each key is only used by one user per Supabase
-- project; if the project is ever multi-tenant, fold user_id into the hash.
--
-- Safe to re-run: all statements are idempotent.
-- =============================================================================

-- Shared updated_at trigger function (created by the invoices migration, but
-- using CREATE OR REPLACE keeps this file self-contained).
create or replace function public.set_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

-- Table (columns match UserSettingRow JSON property names / types)
create table if not exists public.user_settings (
  id          uuid primary key,
  user_id     uuid not null default auth.uid()
				references auth.users (id) on delete cascade,
  key         text not null,
  value       text not null,
  updated_at  timestamptz not null default now(),
  deleted_at  timestamptz
);

-- Index to support the incremental pull query:
-- GET /rest/v1/user_settings?order=updated_at.asc&updated_at=gte.<watermark>
create index if not exists ix_user_settings_user_updated
  on public.user_settings (user_id, updated_at);

-- Stamp updated_at server-side on every insert / update.
drop trigger if exists trg_user_settings_updated_at on public.user_settings;
create trigger trg_user_settings_updated_at
  before insert or update on public.user_settings
  for each row execute function public.set_updated_at();

-- Row-level security: each user sees and writes only their own rows.
-- Soft-deletes are UPDATEs (deleted_at tombstone), so no delete policy needed.
alter table public.user_settings enable row level security;

drop policy if exists "user_settings_select_own" on public.user_settings;
create policy "user_settings_select_own" on public.user_settings
  for select using (auth.uid() = user_id);

drop policy if exists "user_settings_insert_own" on public.user_settings;
create policy "user_settings_insert_own" on public.user_settings
  for insert with check (auth.uid() = user_id);

drop policy if exists "user_settings_update_own" on public.user_settings;
create policy "user_settings_update_own" on public.user_settings
  for update using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

-- Realtime: client subscribes to postgres_changes on public.user_settings.
do $$
begin
  alter publication supabase_realtime add table public.user_settings;
exception when duplicate_object then null;
end;
$$;
