-- =============================================================================
-- Migration: ledger core
-- The accounts, transactions, transaction_entries and assets tables the app syncs.
-- These tables were originally created by hand in the Supabase dashboard; this file
-- captures them so the whole backend lives in the repository.
--
-- Column names and types match the row DTOs in
--   app/src/main/java/com/soloit/doubleentry/sync/SyncRows.kt
--
-- Sync model (shared by every table):
--   * id          client-generated uuid (some are deterministic, see DeterministicIds.kt)
--   * user_id     owner; RLS restricts every row to auth.uid()
--   * updated_at  stamped by the server trigger on every insert/update; clients pull
--                 incrementally with `updated_at >= watermark`
--   * deleted_at  soft-delete tombstone so deletions replicate to other devices
--
-- Safe to re-run: every statement is idempotent and leaves existing data alone.
-- =============================================================================

-- Shared trigger function: the server clock is authoritative for updated_at.
create or replace function public.set_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

-- -----------------------------------------------------------------------------
-- accounts
-- -----------------------------------------------------------------------------
create table if not exists public.accounts (
  id          uuid primary key,
  user_id     uuid not null default auth.uid()
                references auth.users (id) on delete cascade,
  number      integer not null,
  name        text not null,
  -- 0 Asset, 1 Liability, 2 Equity, 3 Revenue, 4 Expense
  type        smallint not null check (type between 0 and 4),
  updated_at  timestamptz not null default now(),
  deleted_at  timestamptz
);

-- -----------------------------------------------------------------------------
-- transactions (journal entries; the lines live in transaction_entries)
-- -----------------------------------------------------------------------------
create table if not exists public.transactions (
  id           uuid primary key,
  user_id      uuid not null default auth.uid()
                 references auth.users (id) on delete cascade,
  date         date not null,
  title        text not null,
  description  text,
  updated_at   timestamptz not null default now(),
  deleted_at   timestamptz
);

-- -----------------------------------------------------------------------------
-- transaction_entries (debit/credit lines)
-- -----------------------------------------------------------------------------
create table if not exists public.transaction_entries (
  id              uuid primary key,
  user_id         uuid not null default auth.uid()
                    references auth.users (id) on delete cascade,
  transaction_id  uuid not null references public.transactions (id) on delete cascade,
  account_id      uuid not null references public.accounts (id),
  amount          numeric(18,2) not null check (amount > 0),
  -- 0 Debit, 1 Credit
  entry_type      smallint not null check (entry_type in (0, 1)),
  updated_at      timestamptz not null default now(),
  deleted_at      timestamptz
);

-- -----------------------------------------------------------------------------
-- assets (depreciated monthly by automatic write-off transactions)
-- -----------------------------------------------------------------------------
create table if not exists public.assets (
  id                      uuid primary key,
  user_id                 uuid not null default auth.uid()
                            references auth.users (id) on delete cascade,
  name                    text not null,
  cost                    numeric(18,2) not null check (cost > 0),
  activation_date         date not null,
  useful_life_months      integer not null check (useful_life_months > 0),
  debit_account_id        uuid not null references public.accounts (id),
  credit_account_id       uuid not null references public.accounts (id),
  -- first day of the most recent month that has been written off
  last_written_off_month  date,
  amount_written_off      numeric(18,2) not null default 0,
  updated_at              timestamptz not null default now(),
  deleted_at              timestamptz
);

-- -----------------------------------------------------------------------------
-- Indexes for the incremental pull:
--   GET /rest/v1/<table>?order=updated_at.asc,id.asc&updated_at=gte.<watermark>
-- -----------------------------------------------------------------------------
create index if not exists ix_accounts_user_updated on public.accounts (user_id, updated_at);
create index if not exists ix_transactions_user_updated on public.transactions (user_id, updated_at);
create index if not exists ix_transaction_entries_user_updated on public.transaction_entries (user_id, updated_at);
create index if not exists ix_assets_user_updated on public.assets (user_id, updated_at);

-- -----------------------------------------------------------------------------
-- updated_at triggers
-- -----------------------------------------------------------------------------
drop trigger if exists trg_accounts_updated_at on public.accounts;
create trigger trg_accounts_updated_at
  before insert or update on public.accounts
  for each row execute function public.set_updated_at();

drop trigger if exists trg_transactions_updated_at on public.transactions;
create trigger trg_transactions_updated_at
  before insert or update on public.transactions
  for each row execute function public.set_updated_at();

drop trigger if exists trg_transaction_entries_updated_at on public.transaction_entries;
create trigger trg_transaction_entries_updated_at
  before insert or update on public.transaction_entries
  for each row execute function public.set_updated_at();

drop trigger if exists trg_assets_updated_at on public.assets;
create trigger trg_assets_updated_at
  before insert or update on public.assets
  for each row execute function public.set_updated_at();

-- -----------------------------------------------------------------------------
-- Row-level security: each user sees and writes only their own rows.
-- Deletes are soft (UPDATE of deleted_at), so no delete policy is granted.
-- -----------------------------------------------------------------------------
alter table public.accounts enable row level security;
alter table public.transactions enable row level security;
alter table public.transaction_entries enable row level security;
alter table public.assets enable row level security;

drop policy if exists "accounts_select_own" on public.accounts;
create policy "accounts_select_own" on public.accounts
  for select using (auth.uid() = user_id);
drop policy if exists "accounts_insert_own" on public.accounts;
create policy "accounts_insert_own" on public.accounts
  for insert with check (auth.uid() = user_id);
drop policy if exists "accounts_update_own" on public.accounts;
create policy "accounts_update_own" on public.accounts
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "transactions_select_own" on public.transactions;
create policy "transactions_select_own" on public.transactions
  for select using (auth.uid() = user_id);
drop policy if exists "transactions_insert_own" on public.transactions;
create policy "transactions_insert_own" on public.transactions
  for insert with check (auth.uid() = user_id);
drop policy if exists "transactions_update_own" on public.transactions;
create policy "transactions_update_own" on public.transactions
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "transaction_entries_select_own" on public.transaction_entries;
create policy "transaction_entries_select_own" on public.transaction_entries
  for select using (auth.uid() = user_id);
drop policy if exists "transaction_entries_insert_own" on public.transaction_entries;
create policy "transaction_entries_insert_own" on public.transaction_entries
  for insert with check (auth.uid() = user_id);
drop policy if exists "transaction_entries_update_own" on public.transaction_entries;
create policy "transaction_entries_update_own" on public.transaction_entries
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

drop policy if exists "assets_select_own" on public.assets;
create policy "assets_select_own" on public.assets
  for select using (auth.uid() = user_id);
drop policy if exists "assets_insert_own" on public.assets;
create policy "assets_insert_own" on public.assets
  for insert with check (auth.uid() = user_id);
drop policy if exists "assets_update_own" on public.assets;
create policy "assets_update_own" on public.assets
  for update using (auth.uid() = user_id) with check (auth.uid() = user_id);

-- -----------------------------------------------------------------------------
-- Realtime: the app subscribes to postgres_changes on every synced table.
-- -----------------------------------------------------------------------------
do $$
begin
  alter publication supabase_realtime add table public.accounts;
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter publication supabase_realtime add table public.transactions;
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter publication supabase_realtime add table public.transaction_entries;
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter publication supabase_realtime add table public.assets;
exception when duplicate_object then null;
end;
$$;
