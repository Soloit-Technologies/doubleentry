-- =============================================================================
-- Migration: a replaced ledger refuses edits from before the replacement
--
-- Replacing a ledger (importing a backup, deleting all data, or a device's ledger replacing the
-- account's at sign-in) tombstones every cloud row last edited before then. Rows that weren't in
-- the cloud yet escaped that, such as the changes of a device that was offline: uploaded later,
-- they came back into the replaced ledger, referring to accounts it no longer has.
--
-- * ledgers.replaced_at: when the ledger was last replaced.
-- * mark_ledger_replaced(ledger, as_of): records a replacement; the app calls it before it
--   tombstones the old rows. Only members who may upload; never later than now, and it never
--   moves back.
-- * A synced row edited before replaced_at is skipped, like an edit older than the stored one:
--   the upsert leaves it out of its response, and the app drops it.
--
-- Safe to re-run.
-- =============================================================================

alter table public.ledgers add column if not exists replaced_at timestamptz;

create or replace function public.mark_ledger_replaced(ledger uuid, as_of timestamptz)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  if ledger not in (select public.writable_ledger_ids()) then
    raise exception 'not_writer';
  end if;
  -- greatest() ignores the null of a ledger never replaced.
  update public.ledgers l set replaced_at = greatest(l.replaced_at, least(as_of, now())) where l.id = ledger;
end;
$$;

revoke all on function public.mark_ledger_replaced(uuid, timestamptz) from public, anon;
grant execute on function public.mark_ledger_replaced(uuid, timestamptz) to authenticated;

-- Only edits count: clearing user_id for a deleted account keeps an old modified_at, and the
-- tombstones of the replacement itself are stamped with it.
create or replace function public.refuse_edits_before_replacement()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if (tg_op = 'INSERT' or new.modified_at is distinct from old.modified_at)
     and new.modified_at < (select l.replaced_at from public.ledgers l where l.id = new.ledger_id) then
    return null;
  end if;
  return new;
end;
$$;

revoke all on function public.refuse_edits_before_replacement() from public, anon, authenticated;

do $$
declare
  t text;
begin
  foreach t in array array['accounts', 'transactions', 'assets', 'invoices', 'user_settings']
  loop
    execute format('drop trigger if exists %I on public.%I', 'trg_' || t || '_after_replacement', t);
    execute format(
      'create trigger %I before insert or update on public.%I for each row execute function public.refuse_edits_before_replacement()',
      'trg_' || t || '_after_replacement', t);
  end loop;
end;
$$;
