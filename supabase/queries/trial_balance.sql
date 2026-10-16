-- =============================================================================
-- Trial balance: balance of every live account as of a date, on its normal side
-- (assets and expenses are debit-normal, everything else credit-normal).
-- Read-only. Adjust the owner's email and the date below (the owner's oldest ledger is used).
-- =============================================================================

with params as (
  select (select l.id from public.ledgers l join auth.users u on u.id = l.owner_id
          where u.email = 'you@example.com' order by l.created_at, l.id limit 1) as ledger_id,
         date '2026-12-31' as as_of
),
totals as (
  select e.account_id,
         sum(case when e.entry_type = 0 then e.amount else 0 end) as debits,
         sum(case when e.entry_type = 1 then e.amount else 0 end) as credits
  from public.transactions t
  cross join lateral jsonb_to_recordset(t.lines) as e(account_id uuid, amount numeric, entry_type smallint)
  cross join params p
  where t.deleted_at is null
    and t.ledger_id = p.ledger_id and t.date <= p.as_of
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
where a.deleted_at is null and a.ledger_id = p.ledger_id
order by a.number;
