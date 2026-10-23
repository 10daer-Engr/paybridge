ALTER TABLE remittance
    ADD COLUMN recipient_name                     VARCHAR(150),
    ADD COLUMN recipient_provider_code            VARCHAR(50),
    ADD COLUMN provider_reference                 VARCHAR(60),
    ADD COLUMN provider_transfer_code             VARCHAR(50),
    ADD COLUMN usd_reversal_ledger_transaction_id UUID,
    ADD COLUMN ngn_reversal_ledger_transaction_id UUID;

CREATE UNIQUE INDEX uq_remittance_provider_reference ON remittance (provider_reference);

-- Small, fast index for the "find stuck remittances" job.
CREATE INDEX idx_remittance_processing ON remittance (status, updated_at) WHERE status = 'PROCESSING';

-- A reversed remittance must point at both reversal postings.
ALTER TABLE remittance ADD CONSTRAINT chk_remittance_reversed_has_reversal CHECK (
    status <> 'REVERSED'
        OR (usd_reversal_ledger_transaction_id IS NOT NULL AND ngn_reversal_ledger_transaction_id IS NOT NULL));