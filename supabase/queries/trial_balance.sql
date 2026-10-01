-- =============================================================================
-- Trial balance: balance of every live account as of a date, on its normal side
-- (assets and expenses are debit-normal, everything else credit-normal).
-- Read-only. Adjust the email and date below.
-- =============================================================================

with params as (
  select (select id from auth.users where email = 'you@example.com') as user_id,
         date '2026-12-31' as as_of
),
totals as (
  select e.account_id,
         sum(case when e.entry_type = 0 then e.amount else 0 end) as debits,
         sum(case when e.entry_type = 1 then e.amount else 0 end) as credits
  from public.transaction_entries e
  join public.transactions t on t.id = e.transaction_id
  cross join params p
  where e.deleted_at is null and t.deleted_at is null
    and t.user_id = p.user_id and t.date <= p.as_of
  group by e.account_id
)
select a.number,
       a.name,
       case a.type when 0 then 'Asset' when 1 then 'Liability' when 2 then 'Equity' when 3 then 'Revenue' else 'Expense' end as type,
       coalesce(t.debits, 0) as debits,
       coalesce(t.credits, 0) as credits,
       case when a.type in (0, 4) then coalesce(t.debits, 0) - coalesce(t.credits, 0)
            else coalesce(t.credits, 0) - coalesce(t.debits, 0) end as balance
from public.accounts a
cross join params p
left join totals t on t.account_id = a.id
where a.deleted_at is null and a.user_id = p.user_id
order by a.number;
