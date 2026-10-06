-- =============================================================================
-- Migration: transactions carry their lines, and edits carry their edit time
--
-- 1. Lines move into `transactions.lines` (jsonb). A transaction and its lines are one row, so they sync together: no device can see a
--    header without its lines or with half of them, and when two devices edit the same
--    transaction, one whole version wins instead of a mix of both.
--    Each line is {"id": uuid, "account_id": uuid, "amount": numeric, "entry_type": 0|1}.
--    `transaction_entries` is no longer used. It is left in place because the earlier
--    migrations recreate and alter it when they are re-run; drop it by hand with
--    queries/drop_transaction_entries.sql once every device runs a current build.
--
-- 2. Every synced table gets `modified_at`: when the edit was made, by the server's clock as
--    the device estimates it. Conflicts are decided by it (last edit wins), and a trigger
--    refuses an update that is older than the stored row, so an edit that reaches the server
--    late can never overwrite a newer one. `updated_at` stays what it was: the moment the row
--    reached the server, stamped by the server, used by clients only to pull what changed.
--
-- Builds of the app from before this migration don't send `modified_at` or `lines`, so their
-- uploads fail (both columns are required); update the app on every device when deploying.
--
-- Safe to re-run.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. modified_at, backfilled with the last change the server knows of
-- -----------------------------------------------------------------------------
do $$
declare
  t text;
begin
  foreach t in array array['accounts', 'transactions', 'assets', 'invoices', 'user_settings']
  loop
    execute format('alter table public.%I add column if not exists modified_at timestamptz', t);
    execute format('update public.%I set modified_at = updated_at where modified_at is null', t);
    execute format('alter table public.%I alter column modified_at set not null', t);
  end loop;
end;
$$;

-- -----------------------------------------------------------------------------
-- 2. transactions.lines, filled from transaction_entries
-- -----------------------------------------------------------------------------
alter table public.transactions add column if not exists lines jsonb;

do $$
begin
  if to_regclass('public.transaction_entries') is not null then
    -- Live lines of live transactions; a deleted transaction keeps the lines it was deleted
    -- with (they share its deletion stamp), so undoing the delete brings them back.
    update public.transactions t
    set lines = coalesce((
      select jsonb_agg(
               jsonb_build_object('id', e.id, 'account_id', e.account_id, 'amount', e.amount, 'entry_type', e.entry_type)
               order by e.entry_type, e.id)
      from public.transaction_entries e
      where e.user_id = t.user_id
        and e.transaction_id = t.id
        and (e.deleted_at is null or e.deleted_at = t.deleted_at)
    ), '[]'::jsonb)
    where t.lines is null;
  end if;
end;
$$;

update public.transactions set lines = '[]'::jsonb where lines is null;
alter table public.transactions alter column lines set not null;

do $$
begin
  alter table public.transactions add constraint transactions_lines_check check (jsonb_typeof(lines) = 'array');
exception when duplicate_object then null;
end;
$$;

-- -----------------------------------------------------------------------------
-- 3. Last edit wins, on the server too
-- -----------------------------------------------------------------------------
-- Returning NULL skips the update: the stored row stays, and an upsert with
-- `return=representation` leaves the row out of its response, which tells the client
-- that a newer edit is already stored.
create or replace function public.keep_newer_edit()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.modified_at < old.modified_at then
    return null;
  end if;
  return new;
end;
$$;

-- Triggers fire in name order: *_keep_newer_edit runs before *_updated_at, so a refused
-- update doesn't move the row's updated_at either.
do $$
declare
  t text;
begin
  foreach t in array array['accounts', 'transactions', 'assets', 'invoices', 'user_settings']
  loop
    execute format('drop trigger if exists %I on public.%I', 'trg_' || t || '_keep_newer_edit', t);
    execute format(
      'create trigger %I before update on public.%I for each row execute function public.keep_newer_edit()',
      'trg_' || t || '_keep_newer_edit', t);
  end loop;
end;
$$;

-- Wiping a ledger (Delete all, Import) tombstones rows by when they were edited.
create index if not exists ix_accounts_user_modified on public.accounts (user_id, modified_at);
create index if not exists ix_transactions_user_modified on public.transactions (user_id, modified_at);
create index if not exists ix_assets_user_modified on public.assets (user_id, modified_at);
create index if not exists ix_invoices_user_modified on public.invoices (user_id, modified_at);
create index if not exists ix_user_settings_user_modified on public.user_settings (user_id, modified_at);
