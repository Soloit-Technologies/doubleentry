-- =============================================================================
-- Migration: invoices
-- Adds the public.invoices table with the columns matching the app's
-- InvoiceRow DTO (SupabaseAutoSyncService.cs), an updated_at trigger,
-- RLS policies scoped to the signed-in user, and Realtime support.
--
-- Prerequisites: public.accounts and public.transactions must already exist.
-- Safe to re-run: all statements are idempotent.
-- =============================================================================

-- Shared updated_at trigger function used by all synced tables.
create or replace function public.set_updated_at()
returns trigger language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

-- Table (columns match InvoiceRow JSON property names / types)
create table if not exists public.invoices (
  id                      uuid primary key,
  user_id                 uuid not null default auth.uid()
							references auth.users (id) on delete cascade,
  amount_due              numeric(18,2) not null,
  invoice_date            date not null,
  due_date                date not null,
  booking_transaction_id  uuid not null references public.transactions (id),
  outstanding_account_id  uuid not null references public.accounts (id),
  outstanding_is_credit   boolean not null,
  is_paid                 boolean not null default false,
  paid_date               date,
  payment_transaction_id  uuid references public.transactions (id),
  paid_from_account_id    uuid references public.accounts (id),
  updated_at              timestamptz not null default now(),
  deleted_at              timestamptz
);

-- Index to support the incremental pull query:
-- GET /rest/v1/invoices?order=updated_at.asc&updated_at=gte.<watermark>
create index if not exists ix_invoices_user_updated
  on public.invoices (user_id, updated_at);

-- Stamp updated_at server-side on every insert / update so the watermark
-- is authoritative regardless of client clock skew.
drop trigger if exists trg_invoices_updated_at on public.invoices;
create trigger trg_invoices_updated_at
  before insert or update on public.invoices
  for each row execute function public.set_updated_at();

-- Row-level security: each user sees and writes only their own rows.
-- Soft-deletes are UPDATEs (deleted_at tombstone), so no delete policy needed.
alter table public.invoices enable row level security;

drop policy if exists "invoices_select_own" on public.invoices;
create policy "invoices_select_own" on public.invoices
  for select using (auth.uid() = user_id);

drop policy if exists "invoices_insert_own" on public.invoices;
create policy "invoices_insert_own" on public.invoices
  for insert with check (auth.uid() = user_id);

drop policy if exists "invoices_update_own" on public.invoices;
create policy "invoices_update_own" on public.invoices
  for update using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

-- Realtime: client subscribes to postgres_changes on public.invoices.
do $$
begin
  alter publication supabase_realtime add table public.invoices;
exception when duplicate_object then null;
end;
$$;
