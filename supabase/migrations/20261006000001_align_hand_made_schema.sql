-- =============================================================================
-- Migration: align the hand-made tables with the migrations
-- The project's first tables were created in the dashboard, and `create table if not exists`
-- left them as they were. This brings them in line with what the migrations describe:
--
--   * drops the dashboard's catch-all policies ("accounts: user owns rows", "owner", …): they
--     apply to every role and command, including hard DELETE, which the sync design leaves out
--     (deletes are tombstones), and they duplicate the per-command policies
--   * drops indexes that duplicate the ix_*_user_updated ones
--   * adds the value checks and column defaults of the table definitions
--   * deletes a user's settings with the user (ON DELETE CASCADE), like every other table
--   * keeps settings keys unique per user (the live project already had this index)
--
-- Safe to re-run, and a no-op on a project created from the migrations alone.
-- =============================================================================

drop policy if exists "accounts: user owns rows" on public.accounts;
drop policy if exists "assets: user owns rows" on public.assets;
drop policy if exists "entries: user owns rows" on public.transaction_entries;
drop policy if exists "transactions: user owns rows" on public.transactions;
drop policy if exists "owner" on public.user_settings;

drop index if exists public.accounts_user_updated;
drop index if exists public.assets_user_updated;
drop index if exists public.entries_user_updated;
drop index if exists public.transactions_user_updated;

-- Checks (named as Postgres names the inline checks of the original table definitions).
do $$
begin
  alter table public.accounts add constraint accounts_type_check check (type between 0 and 4);
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter table public.transaction_entries add constraint transaction_entries_amount_check check (amount > 0);
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter table public.transaction_entries add constraint transaction_entries_entry_type_check check (entry_type in (0, 1));
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter table public.assets add constraint assets_cost_check check (cost > 0);
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter table public.assets add constraint assets_useful_life_months_check check (useful_life_months > 0);
exception when duplicate_object then null;
end;
$$;

-- Defaults (the app always sends these columns; the trigger stamps updated_at anyway).
alter table public.accounts alter column user_id set default auth.uid();
alter table public.accounts alter column updated_at set default now();
alter table public.transactions alter column user_id set default auth.uid();
alter table public.transactions alter column updated_at set default now();
alter table public.transaction_entries alter column user_id set default auth.uid();
alter table public.transaction_entries alter column updated_at set default now();
alter table public.assets alter column user_id set default auth.uid();
alter table public.assets alter column updated_at set default now();
alter table public.assets alter column amount_written_off set default 0;
alter table public.user_settings alter column user_id set default auth.uid();

-- A user's settings go with the user.
alter table public.user_settings drop constraint if exists user_settings_user_id_fkey;
alter table public.user_settings add constraint user_settings_user_id_fkey
  foreign key (user_id) references auth.users (id) on delete cascade;

-- One row per setting key and user (settings ids are derived from the key).
create unique index if not exists user_settings_user_key_uq on public.user_settings (user_id, key);
