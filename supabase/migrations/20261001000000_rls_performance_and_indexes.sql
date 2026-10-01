-- =============================================================================
-- Migration: RLS performance, function hardening and foreign-key indexes
--
-- * Policies use `(select auth.uid())` so Postgres evaluates the user id once per
--   query instead of once per row (Supabase advisor: auth_rls_initplan).
-- * set_updated_at() gets a fixed, empty search_path (advisor: function_search_path_mutable).
-- * Foreign-key columns get indexes so joins and FK checks stay fast.
--
-- Safe to re-run; changes no data.
-- =============================================================================

create or replace function public.set_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;

-- Recreate select/insert/update policies for every synced table.
do $$
declare
  t text;
begin
  foreach t in array array['accounts', 'transactions', 'transaction_entries', 'assets', 'invoices', 'user_settings']
  loop
    execute format('drop policy if exists %I on public.%I', t || '_select_own', t);
    execute format(
      'create policy %I on public.%I for select to authenticated using ((select auth.uid()) = user_id)',
      t || '_select_own', t);

    execute format('drop policy if exists %I on public.%I', t || '_insert_own', t);
    execute format(
      'create policy %I on public.%I for insert to authenticated with check ((select auth.uid()) = user_id)',
      t || '_insert_own', t);

    execute format('drop policy if exists %I on public.%I', t || '_update_own', t);
    execute format(
      'create policy %I on public.%I for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id)',
      t || '_update_own', t);
  end loop;
end;
$$;

create index if not exists ix_transaction_entries_transaction on public.transaction_entries (transaction_id);
create index if not exists ix_transaction_entries_account on public.transaction_entries (account_id);
create index if not exists ix_assets_debit_account on public.assets (debit_account_id);
create index if not exists ix_assets_credit_account on public.assets (credit_account_id);
create index if not exists ix_invoices_booking_transaction on public.invoices (booking_transaction_id);
create index if not exists ix_invoices_payment_transaction on public.invoices (payment_transaction_id);
create index if not exists ix_invoices_outstanding_account on public.invoices (outstanding_account_id);
create index if not exists ix_invoices_paid_from_account on public.invoices (paid_from_account_id);
