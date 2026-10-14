-- 1. A balance floor, so a partner position account can go negative only down to its credit limit.
ALTER TABLE account ADD COLUMN balance_floor_minor BIGINT;

-- 2. A third account type.
ALTER TABLE account DROP CONSTRAINT chk_account_type;
ALTER TABLE account ADD CONSTRAINT chk_account_type
    CHECK (account_type IN ('CUSTOMER', 'SYSTEM', 'PARTNER'));

-- 3. The balance rule now depends on the type of account.
ALTER TABLE account DROP CONSTRAINT chk_account_balance_non_negative;
ALTER TABLE account ADD CONSTRAINT chk_account_balance_rule CHECK (
    CASE account_type
        WHEN 'CUSTOMER' THEN balance_minor >= 0
        WHEN 'PARTNER'  THEN balance_floor_minor IS NOT NULL AND balance_minor >= balance_floor_minor
        ELSE TRUE
        END
    );

-- 4. A partner has exactly one position account per currency.
CREATE UNIQUE INDEX uq_partner_account ON account (customer_id, currency) WHERE account_type = 'PARTNER';

-- 5. A shared id that ties together the legs of one business event (for example both sides of an exchange).
ALTER TABLE ledger_transaction ADD COLUMN correlation_id VARCHAR(100);
CREATE INDEX idx_ledger_transaction_correlation
    ON ledger_transaction (correlation_id) WHERE correlation_id IS NOT NULL;

-- 6. The system accounts that exchanges post to.
INSERT INTO account
(id, account_number, customer_id, currency, balance_minor, status, account_type, version, created_at, updated_at)
VALUES
    ('00000000-0000-0000-0000-0000000000a3', '0000000003', '00000000-0000-0000-0000-000000000000', 'USD', 0, 'ACTIVE', 'SYSTEM', 0, now(), now()),
    ('00000000-0000-0000-0000-0000000000a4', '0000000004', '00000000-0000-0000-0000-000000000000', 'NGN', 0, 'ACTIVE', 'SYSTEM', 0, now(), now()),
    ('00000000-0000-0000-0000-0000000000a5', '0000000005', '00000000-0000-0000-0000-000000000000', 'USD', 0, 'ACTIVE', 'SYSTEM', 0, now(), now()),
    ('00000000-0000-0000-0000-0000000000a6', '0000000006', '00000000-0000-0000-0000-000000000000', 'NGN', 0, 'ACTIVE', 'SYSTEM', 0, now(), now());