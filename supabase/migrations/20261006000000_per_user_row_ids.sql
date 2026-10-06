-- =============================================================================
-- Migration: per-user row ids
-- Every synced table is keyed by (user_id, id) instead of id alone, and references between
-- tables include user_id.
--
-- Some ids are derived from content only (default chart of accounts from the account number,
-- settings from their key, depreciation and recurring postings from asset/rule and month), so
-- they are the same for every user. With a global key, a second user's upsert of such a row
-- hit the first user's row and failed row-level security, and a ledger could not be copied to
-- another account. Per-user keys give each user their own id space.
--
-- Clients upsert with `on_conflict=user_id,id`. Builds that still send `on_conflict=id` fail
-- after this migration and must be updated.
--
-- Safe to re-run: constraints are only replaced when they still have the old shape.
-- =============================================================================

do $$
declare
  t text;
  c record;
begin
  -- 1. Single-column foreign keys between the synced tables depend on the old primary keys.
  for c in
    select con.conname, rel.relname
    from pg_constraint con
    join pg_class rel on rel.oid = con.conrelid
    join pg_namespace ns on ns.oid = rel.relnamespace
    join pg_class ref on ref.oid = con.confrelid
    where con.contype = 'f'
      and ns.nspname = 'public'
      and ref.relname in ('accounts', 'transactions', 'transaction_entries', 'assets', 'invoices', 'user_settings')
      and array_length(con.conkey, 1) = 1
  loop
    execute format('alter table public.%I drop constraint %I', c.relname, c.conname);
  end loop;

  -- 2. Primary keys: (id) → (user_id, id).
  foreach t in array array['accounts', 'transactions', 'transaction_entries', 'assets', 'invoices', 'user_settings']
  loop
    select con.conname into c
    from pg_constraint con
    where con.conrelid = format('public.%I', t)::regclass
      and con.contype = 'p'
      and array_length(con.conkey, 1) = 1;
    if found then
      execute format('alter table public.%I drop constraint %I', t, c.conname);
      execute format('alter table public.%I add constraint %I primary key (user_id, id)', t, t || '_pkey');
    end if;
  end loop;
end;
$$;

-- 3. References within one user's ledger. A row can only point at the same user's rows.
do $$
begin
  alter table public.transaction_entries add constraint transaction_entries_transaction_fkey
    foreign key (user_id, transaction_id) references public.transactions (user_id, id) on delete cascade;
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter table public.transaction_entries add constraint transaction_entries_account_fkey
    foreign key (user_id, account_id) references public.accounts (user_id, id);
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter table public.assets add constraint assets_debit_account_fkey
    foreign key (user_id, debit_account_id) references public.accounts (user_id, id);
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter table public.assets add constraint assets_credit_account_fkey
    foreign key (user_id, credit_account_id) references public.accounts (user_id, id);
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter table public.invoices add constraint invoices_booking_transaction_fkey
    foreign key (user_id, booking_transaction_id) references public.transactions (user_id, id);
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter table public.invoices add constraint invoices_outstanding_account_fkey
    foreign key (user_id, outstanding_account_id) references public.accounts (user_id, id);
exception when duplicate_object then null;
end;
$$;

-- Nullable columns: a NULL reference is not checked (MATCH SIMPLE), as before.
do $$
begin
  alter table public.invoices add constraint invoices_payment_transaction_fkey
    foreign key (user_id, payment_transaction_id) references public.transactions (user_id, id);
exception when duplicate_object then null;
end;
$$;

do $$
begin
  alter table public.invoices add constraint invoices_paid_from_account_fkey
    foreign key (user_id, paid_from_account_id) references public.accounts (user_id, id);
exception when duplicate_object then null;
end;
$$;
