CREATE TABLE partner (
                         id                      UUID         PRIMARY KEY,
                         name                    VARCHAR(150) NOT NULL,
                         contact_email           VARCHAR(254) NOT NULL,
                         api_key_hash            VARCHAR(64)  NOT NULL,
                         status                  VARCHAR(20)  NOT NULL,
                         credit_limit_usd_minor  BIGINT       NOT NULL,
                         created_at              TIMESTAMPTZ  NOT NULL,

                         CONSTRAINT chk_partner_status CHECK (status IN ('ACTIVE', 'SUSPENDED')),
                         CONSTRAINT chk_partner_credit_limit CHECK (credit_limit_usd_minor >= 0)
);

CREATE UNIQUE INDEX uq_partner_name ON partner (lower(name));
CREATE UNIQUE INDEX uq_partner_api_key_hash ON partner (api_key_hash);