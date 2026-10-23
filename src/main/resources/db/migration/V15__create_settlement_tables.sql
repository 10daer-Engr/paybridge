CREATE TABLE settlement_batch (
                                  id                     UUID        PRIMARY KEY,
                                  partner_id             UUID        NOT NULL,
                                  reference              VARCHAR(40) NOT NULL UNIQUE,
                                  status                 VARCHAR(10) NOT NULL,
                                  reconciliation_status  VARCHAR(10) NOT NULL,
                                  item_count             INTEGER     NOT NULL,
                                  gross_usd_minor        BIGINT      NOT NULL,
                                  fee_usd_minor          BIGINT      NOT NULL,
                                  payout_ngn_minor       BIGINT      NOT NULL,
                                  paid_usd_minor         BIGINT      NOT NULL DEFAULT 0,
                                  created_at             TIMESTAMPTZ NOT NULL,
                                  closed_at              TIMESTAMPTZ,
                                  version                BIGINT      NOT NULL DEFAULT 0,

                                  CONSTRAINT chk_settlement_status CHECK (status IN ('OPEN', 'CLOSED')),
                                  CONSTRAINT chk_settlement_recon CHECK (reconciliation_status IN ('PENDING', 'MATCHED', 'BREAKS')),
                                  CONSTRAINT chk_settlement_amounts CHECK (
                                      item_count > 0 AND gross_usd_minor > 0 AND fee_usd_minor >= 0 AND payout_ngn_minor > 0
                                          AND paid_usd_minor >= 0 AND paid_usd_minor <= gross_usd_minor),
    -- A batch can only be closed when fully paid and reconciled with no breaks.
                                  CONSTRAINT chk_settlement_closed CHECK (
                                      status <> 'CLOSED'
                                          OR (paid_usd_minor = gross_usd_minor AND reconciliation_status = 'MATCHED' AND closed_at IS NOT NULL))
);

CREATE INDEX idx_settlement_batch_partner ON settlement_batch (partner_id, created_at DESC);

CREATE TABLE settlement_item (
                                 id                    UUID        PRIMARY KEY,
                                 partner_id            UUID        NOT NULL,
                                 remittance_id         UUID        NOT NULL UNIQUE,   -- a remittance can be settled only once
                                 batch_id              UUID        REFERENCES settlement_batch (id),
                                 reference             VARCHAR(64) NOT NULL,
                                 send_amount_minor     BIGINT      NOT NULL,
                                 fee_minor             BIGINT      NOT NULL,
                                 receive_amount_minor  BIGINT      NOT NULL,
                                 completed_at          TIMESTAMPTZ NOT NULL,
                                 created_at            TIMESTAMPTZ NOT NULL
);

-- Small, fast index for "what is waiting to be settled for this partner?"
CREATE INDEX idx_settlement_item_unbatched ON settlement_item (partner_id, completed_at) WHERE batch_id IS NULL;
CREATE INDEX idx_settlement_item_batch ON settlement_item (batch_id);

CREATE TABLE settlement_payment (
                                    id                    UUID        PRIMARY KEY,
                                    batch_id              UUID        NOT NULL REFERENCES settlement_batch (id),
                                    partner_id            UUID        NOT NULL,
                                    idempotency_key       VARCHAR(80) NOT NULL UNIQUE,
                                    wire_reference        VARCHAR(64) NOT NULL,
                                    amount_usd_minor      BIGINT      NOT NULL,
                                    ledger_transaction_id UUID        NOT NULL,
                                    created_at            TIMESTAMPTZ NOT NULL,
                                    CONSTRAINT chk_settlement_payment_amount CHECK (amount_usd_minor > 0)
);

CREATE INDEX idx_settlement_payment_batch ON settlement_payment (batch_id);

CREATE TABLE settlement_reconciliation (
                                           id                      UUID        PRIMARY KEY,
                                           batch_id                UUID        NOT NULL REFERENCES settlement_batch (id),
                                           status                  VARCHAR(10) NOT NULL,
                                           line_count              INTEGER     NOT NULL,
                                           statement_total_minor   BIGINT      NOT NULL,
                                           our_total_minor         BIGINT      NOT NULL,
                                           break_count             INTEGER     NOT NULL,
                                           created_at              TIMESTAMPTZ NOT NULL,
                                           CONSTRAINT chk_settlement_reconciliation_status CHECK (status IN ('MATCHED', 'BREAKS'))
);

CREATE INDEX idx_settlement_reconciliation_batch ON settlement_reconciliation (batch_id, created_at);

CREATE TABLE settlement_break (
                                  id                  UUID        PRIMARY KEY,
                                  reconciliation_id   UUID        NOT NULL REFERENCES settlement_reconciliation (id),
                                  kind                VARCHAR(30) NOT NULL,
                                  reference           VARCHAR(64) NOT NULL,
                                  our_amount_minor    BIGINT,
                                  their_amount_minor  BIGINT
);

CREATE INDEX idx_settlement_break_reconciliation ON settlement_break (reconciliation_id);

-- PayBridge's own USD bank account, where partner payments arrive.
INSERT INTO account
(id, account_number, customer_id, currency, balance_minor, status, account_type, version, created_at, updated_at)
VALUES
    ('00000000-0000-0000-0000-0000000000a7', '0000000007', '00000000-0000-0000-0000-000000000000',
     'USD', 0, 'ACTIVE', 'SYSTEM', 0, now(), now());