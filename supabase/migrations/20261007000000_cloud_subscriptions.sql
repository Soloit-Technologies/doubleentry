-- =============================================================================
-- Migration: paid cloud backup
--
-- * cloud_subscriptions: one row per user saying until when they may upload. Rows come from a
--   Google Play subscription (written by the play-verify and play-notifications Edge Functions
--   with the service role) or are complimentary (written by hand, no end date).
-- * Uploads (insert/update) to the synced tables require an active row. Reads stay open, so a
--   user whose subscription ended can still download, and restore, everything already backed up.
-- * Every user that exists when this runs gets complimentary access, so ledgers already syncing
--   keep working.
--
-- Safe to re-run; changes no ledger data.
-- =============================================================================

create table if not exists public.cloud_subscriptions (
  user_id        uuid primary key references auth.users (id) on delete cascade,
  source         text not null check (source in ('google_play', 'complimentary')),
  product_id     text,
  base_plan_id   text,
  -- Google Play purchase token; one token belongs to one user.
  purchase_token text unique,
  -- Google's SubscriptionState without the prefix, e.g. active, in_grace_period, canceled,
  -- on_hold, paused, expired; 'active' for complimentary rows.
  state          text not null,
  -- Uploads are allowed until then; null means no end.
  expires_at     timestamptz,
  auto_renewing  boolean not null default false,
  updated_at     timestamptz not null default now()
);

alter table public.cloud_subscriptions enable row level security;

-- Users read their own row; only the service role (Edge Functions, SQL editor) writes.
drop policy if exists cloud_subscriptions_select_own on public.cloud_subscriptions;
create policy cloud_subscriptions_select_own on public.cloud_subscriptions
  for select to authenticated using ((select auth.uid()) = user_id);

drop trigger if exists trg_cloud_subscriptions_updated_at on public.cloud_subscriptions;
create trigger trg_cloud_subscriptions_updated_at
  before update on public.cloud_subscriptions
  for each row execute function public.set_updated_at();

-- True when the signed-in user may upload. Security definer so policies can call it cheaply.
create or replace function public.has_cloud_access()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.cloud_subscriptions s
    where s.user_id = (select auth.uid())
      and (s.expires_at is null or s.expires_at > now())
  );
$$;

-- What the app shows: `POST /rest/v1/rpc/cloud_access`. Always one row, also without a subscription.
create or replace function public.cloud_access()
returns table (active boolean, source text, base_plan_id text, state text, expires_at timestamptz, auto_renewing boolean)
language sql
stable
security definer
set search_path = ''
as $$
  select
    s.user_id is not null and (s.expires_at is null or s.expires_at > now()),
    s.source, s.base_plan_id, s.state, s.expires_at, coalesce(s.auto_renewing, false)
  from (select 1) as one
  left join public.cloud_subscriptions s on s.user_id = (select auth.uid());
$$;

revoke all on function public.has_cloud_access() from public, anon;
revoke all on function public.cloud_access() from public, anon;
grant execute on function public.has_cloud_access() to authenticated;
grant execute on function public.cloud_access() to authenticated;

-- Uploads need access; reads (the select policies) are unchanged.
do $$
declare
  t text;
begin
  foreach t in array array['accounts', 'transactions', 'transaction_entries', 'assets', 'invoices', 'user_settings']
  loop
    continue when to_regclass('public.' || t) is null;
    execute format('drop policy if exists %I on public.%I', t || '_insert_own', t);
    execute format(
      'create policy %I on public.%I for insert to authenticated with check ((select auth.uid()) = user_id and (select public.has_cloud_access()))',
      t || '_insert_own', t);

    execute format('drop policy if exists %I on public.%I', t || '_update_own', t);
    execute format(
      'create policy %I on public.%I for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id and (select public.has_cloud_access()))',
      t || '_update_own', t);
  end loop;
end;
$$;

-- Accounts made before paid backup keep syncing for free.
insert into public.cloud_subscriptions (user_id, source, state)
select id, 'complimentary', 'active' from auth.users
on conflict (user_id) do nothing;
