CREATE TABLE remittance (
                            id                          UUID           PRIMARY KEY,
                            partner_id                  UUID           NOT NULL,
                            idempotency_key             VARCHAR(80)    NOT NULL UNIQUE,
                            partner_reference           VARCHAR(64),
                            quote_id                    UUID           NOT NULL,
                            status                      VARCHAR(20)    NOT NULL,
                            sender_name                 VARCHAR(150)   NOT NULL,
                            sender_country              VARCHAR(2)     NOT NULL,
                            recipient_type              VARCHAR(10)    NOT NULL,
                            recipient_account_id        UUID,
                            recipient_account_number    VARCHAR(10)    NOT NULL,
                            recipient_bank_code         VARCHAR(10),
                            send_amount_minor           BIGINT         NOT NULL,
                            fee_minor                   BIGINT         NOT NULL,
                            receive_amount_minor        BIGINT         NOT NULL,
                            customer_rate               NUMERIC(20, 6) NOT NULL,
                            usd_ledger_transaction_id   UUID,
                            ngn_ledger_transaction_id   UUID,
                            failure_reason              VARCHAR(255),
                            version                     BIGINT         NOT NULL DEFAULT 0,
                            created_at                  TIMESTAMPTZ    NOT NULL,
                            updated_at                  TIMESTAMPTZ    NOT NULL,
                            completed_at                TIMESTAMPTZ,

                            CONSTRAINT chk_remittance_status
                                CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'REVERSED')),
                            CONSTRAINT chk_remittance_recipient_type CHECK (recipient_type IN ('ACCOUNT', 'BANK')),
                            CONSTRAINT chk_remittance_recipient CHECK (
                                (recipient_type = 'ACCOUNT' AND recipient_account_id IS NOT NULL)
                                    OR (recipient_type = 'BANK' AND recipient_bank_code IS NOT NULL)),
                            CONSTRAINT chk_remittance_amounts
                                CHECK (send_amount_minor > 0 AND fee_minor >= 0 AND receive_amount_minor > 0),
                            CONSTRAINT chk_remittance_country CHECK (sender_country ~ '^[A-Z]{2}$'),
    CONSTRAINT chk_remittance_completed_has_ledger CHECK (
        status <> 'COMPLETED'
        OR (usd_ledger_transaction_id IS NOT NULL AND ngn_ledger_transaction_id IS NOT NULL))
);

-- A quote can fund at most one remittance (backstop for the row lock).
CREATE UNIQUE INDEX uq_remittance_quote ON remittance (quote_id);

-- A partner cannot reuse its own reference for a second payment.
CREATE UNIQUE INDEX uq_remittance_partner_reference
    ON remittance (partner_id, partner_reference) WHERE partner_reference IS NOT NULL;

CREATE INDEX idx_remittance_partner ON remittance (partner_id, created_at DESC);