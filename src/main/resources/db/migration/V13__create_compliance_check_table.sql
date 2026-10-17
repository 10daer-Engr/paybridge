CREATE TABLE compliance_check (
                                  id                  UUID         PRIMARY KEY,
                                  partner_id          UUID         NOT NULL,
                                  subject_ref         VARCHAR(80)  NOT NULL,
                                  outcome             VARCHAR(10)  NOT NULL,
                                  reasons             VARCHAR(500) NOT NULL DEFAULT '',
                                  sender_name         VARCHAR(150) NOT NULL,
                                  sender_country      VARCHAR(2)   NOT NULL,
                                  sender_fingerprint  VARCHAR(64)  NOT NULL,
                                  recipient_ref       VARCHAR(40)  NOT NULL,
                                  amount_minor        BIGINT       NOT NULL,
                                  created_at          TIMESTAMPTZ  NOT NULL,

                                  CONSTRAINT chk_compliance_outcome CHECK (outcome IN ('APPROVED', 'REJECTED', 'REFERRED')),
                                  CONSTRAINT chk_compliance_amount CHECK (amount_minor > 0)
);

-- The three limit checks each sum recent approvals for one key.
CREATE INDEX idx_compliance_sender    ON compliance_check (sender_fingerprint, created_at);
CREATE INDEX idx_compliance_recipient ON compliance_check (recipient_ref, created_at);
CREATE INDEX idx_compliance_partner   ON compliance_check (partner_id, created_at);
CREATE INDEX idx_compliance_subject   ON compliance_check (subject_ref);

-- Append-only: an audit trail that can be edited is not an audit trail.
CREATE FUNCTION forbid_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Audit tables are append-only (% on %)', TG_OP, TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_compliance_check_immutable
    BEFORE UPDATE OR DELETE ON compliance_check
    FOR EACH ROW EXECUTE FUNCTION forbid_audit_mutation();