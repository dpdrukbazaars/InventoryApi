-- DP DrukBazaars: reserve a bank debit durably before calling the external bank.
-- Never store account numbers or one-time codes in this table.
-- 13-character DEBIT_PENDING needs a wider status column (was varchar(12)).
-- Existing rows remain unchanged; the timestamp is intentionally nullable.
ALTER TABLE bank_payments MODIFY COLUMN status VARCHAR(16) NOT NULL;
ALTER TABLE bank_payments ADD COLUMN debit_requested_at DATETIME(6) NULL;
