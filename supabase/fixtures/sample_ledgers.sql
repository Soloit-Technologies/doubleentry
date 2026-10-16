-- =============================================================================
-- Sample data in the current schema, for the upgrade check in .github/workflows/supabase.yml:
-- a pull request's new migrations are applied to a database that holds this data in the schema
-- before them (the base commit's migrations and its copy of this file), and must keep it.
--
-- When a migration changes the schema, update this file in the same pull request so it fits
-- the new schema (the next change is then checked against it). Cover new tables and columns.
--
-- Two users: Ada owns a ledger shared with Bo (editor), with a bit of everything in it,
-- deleted rows included; Bo also has an empty ledger and no subscription.
-- =============================================================================

insert into auth.users (id, email, aud, role) values
  ('a0000000-0000-4000-8000-00000000000a', 'ada@example.com', 'authenticated', 'authenticated'),
  ('b0000000-0000-4000-8000-00000000000b', 'bo@example.com', 'authenticated', 'authenticated');

insert into public.cloud_subscriptions (user_id, source, product_id, base_plan_id, purchase_token, state, expires_at, auto_renewing)
values ('a0000000-0000-4000-8000-00000000000a', 'google_play', 'cloud_backup', 'yearly', 'token-ada', 'active', now() + interval '300 days', true);

insert into public.ledgers (id, owner_id, name) values
  ('1ed00000-0000-4000-8000-00000000000a', 'a0000000-0000-4000-8000-00000000000a', 'Household'),
  ('1ed00000-0000-4000-8000-00000000000b', 'b0000000-0000-4000-8000-00000000000b', 'My ledger');
insert into public.ledger_members (ledger_id, user_id, role)
values ('1ed00000-0000-4000-8000-00000000000a', 'b0000000-0000-4000-8000-00000000000b', 'editor');

insert into public.accounts (ledger_id, id, user_id, number, name, type, modified_at, updated_at, deleted_at) values
  ('1ed00000-0000-4000-8000-00000000000a', 'acc00000-0000-4000-8000-000000001910', 'a0000000-0000-4000-8000-00000000000a', 1910, 'Bank', 0, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', null),
  ('1ed00000-0000-4000-8000-00000000000a', 'acc00000-0000-4000-8000-000000001220', 'a0000000-0000-4000-8000-00000000000a', 1220, 'Equipment', 0, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', null),
  ('1ed00000-0000-4000-8000-00000000000a', 'acc00000-0000-4000-8000-000000001510', 'a0000000-0000-4000-8000-00000000000a', 1510, 'Receivables', 0, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', null),
  ('1ed00000-0000-4000-8000-00000000000a', 'acc00000-0000-4000-8000-000000003010', 'a0000000-0000-4000-8000-00000000000a', 3010, 'Sales', 3, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', null),
  ('1ed00000-0000-4000-8000-00000000000a', 'acc00000-0000-4000-8000-000000005010', 'b0000000-0000-4000-8000-00000000000b', 5010, 'Food', 4, '2026-02-01T00:00:00Z', '2026-02-01T00:00:01Z', null),
  ('1ed00000-0000-4000-8000-00000000000a', 'acc00000-0000-4000-8000-000000007830', 'a0000000-0000-4000-8000-00000000000a', 7830, 'Depreciation', 4, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', null),
  ('1ed00000-0000-4000-8000-00000000000a', 'acc00000-0000-4000-8000-000000006990', 'a0000000-0000-4000-8000-00000000000a', 6990, 'Old expenses', 4, '2026-03-01T00:00:00Z', '2026-03-01T00:00:01Z', '2026-03-01T00:00:00Z');

insert into public.transactions (ledger_id, id, user_id, date, title, description, lines, modified_at, updated_at, deleted_at) values
  ('1ed00000-0000-4000-8000-00000000000a', '7c000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-00000000000b', '2026-02-03', 'Groceries', 'ICA',
   '[{"id": "11000000-0000-4000-8000-000000000001", "account_id": "acc00000-0000-4000-8000-000000005010", "amount": 245.50, "entry_type": 0},
     {"id": "11000000-0000-4000-8000-000000000002", "account_id": "acc00000-0000-4000-8000-000000001910", "amount": 245.50, "entry_type": 1}]',
   '2026-02-03T10:00:00.123456Z', '2026-02-03T10:00:01Z', null),
  ('1ed00000-0000-4000-8000-00000000000a', '7c000000-0000-4000-8000-000000000002', 'a0000000-0000-4000-8000-00000000000a', '2026-02-10', 'Invoice 1001', null,
   '[{"id": "11000000-0000-4000-8000-000000000003", "account_id": "acc00000-0000-4000-8000-000000001510", "amount": 12500.00, "entry_type": 0},
     {"id": "11000000-0000-4000-8000-000000000004", "account_id": "acc00000-0000-4000-8000-000000003010", "amount": 12500.00, "entry_type": 1}]',
   '2026-02-10T09:00:00Z', '2026-02-10T09:00:01Z', null),
  ('1ed00000-0000-4000-8000-00000000000a', '7c000000-0000-4000-8000-000000000003', 'a0000000-0000-4000-8000-00000000000a', '2026-02-11', 'Laptop', null,
   '[{"id": "11000000-0000-4000-8000-000000000005", "account_id": "acc00000-0000-4000-8000-000000001220", "amount": 18000.00, "entry_type": 0},
     {"id": "11000000-0000-4000-8000-000000000006", "account_id": "acc00000-0000-4000-8000-000000001910", "amount": 18000.00, "entry_type": 1}]',
   '2026-02-11T09:00:00Z', '2026-02-11T09:00:01Z', null),
  ('1ed00000-0000-4000-8000-00000000000a', '7c000000-0000-4000-8000-000000000004', 'a0000000-0000-4000-8000-00000000000a', '2026-02-12', 'Mistake', null,
   '[{"id": "11000000-0000-4000-8000-000000000007", "account_id": "acc00000-0000-4000-8000-000000005010", "amount": 1.00, "entry_type": 0},
     {"id": "11000000-0000-4000-8000-000000000008", "account_id": "acc00000-0000-4000-8000-000000001910", "amount": 1.00, "entry_type": 1}]',
   '2026-02-12T09:00:00Z', '2026-02-12T09:00:01Z', '2026-02-12T09:00:00Z');

insert into public.assets (ledger_id, id, user_id, name, cost, activation_date, useful_life_months, debit_account_id, credit_account_id,
                           last_written_off_month, amount_written_off, modified_at, updated_at, deleted_at)
values ('1ed00000-0000-4000-8000-00000000000a', 'a5500000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-00000000000a', 'Laptop', 18000.00, '2026-02-11', 36,
        'acc00000-0000-4000-8000-000000007830', 'acc00000-0000-4000-8000-000000001220', '2026-09-01', 4000.00, '2026-09-01T00:00:00Z', '2026-09-01T00:00:01Z', null);

insert into public.invoices (ledger_id, id, user_id, amount_due, invoice_date, due_date, booking_transaction_id, outstanding_account_id, outstanding_is_credit,
                             is_paid, paid_date, payment_transaction_id, paid_from_account_id, modified_at, updated_at, deleted_at)
values ('1ed00000-0000-4000-8000-00000000000a', '1a000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-00000000000a', 12500.00, '2026-02-10', '2026-03-12',
        '7c000000-0000-4000-8000-000000000002', 'acc00000-0000-4000-8000-000000001510', false, false, null, null, null, '2026-02-10T09:00:00Z', '2026-02-10T09:00:01Z', null);

insert into public.user_settings (ledger_id, id, user_id, key, value, modified_at, updated_at, deleted_at) values
  ('1ed00000-0000-4000-8000-00000000000a', '5e000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-00000000000a', 'IncomeStatement.TaxPrefixes', '89', '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', null),
  ('1ed00000-0000-4000-8000-00000000000b', '5e000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-00000000000b', 'IncomeStatement.TaxPrefixes', '26', '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z', null);

insert into public.ledger_invites (code_hash, ledger_id, role, created_by)
values (public.invite_code_hash('WXYZ-2345-6789-TVRS'), '1ed00000-0000-4000-8000-00000000000a', 'editor', 'a0000000-0000-4000-8000-00000000000a');

update public.service_control set min_version_code = 13;

insert into public.sync_error_reports (user_id, app_version, kind, detail, pending_changes)
values ('b0000000-0000-4000-8000-00000000000b', 13, 'SyncException < IOException', 'POST /rest/v1/transactions: HTTP 400. code=23514', 2);
