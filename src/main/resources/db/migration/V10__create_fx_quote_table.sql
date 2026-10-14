CREATE TABLE fx_quote (
                          id                    UUID           PRIMARY KEY,
                          requester_id          UUID           NOT NULL,
                          from_currency         VARCHAR(3)     NOT NULL,
                          to_currency           VARCHAR(3)     NOT NULL,
                          send_amount_minor     BIGINT         NOT NULL,
                          fee_minor             BIGINT         NOT NULL,
                          mid_rate              NUMERIC(20, 6) NOT NULL,
                          spread_bps            INTEGER        NOT NULL,
                          customer_rate         NUMERIC(20, 6) NOT NULL,
                          receive_amount_minor  BIGINT         NOT NULL,
                          margin_minor          BIGINT         NOT NULL,
                          rate_source           VARCHAR(30)    NOT NULL,
                          rate_as_of            TIMESTAMPTZ    NOT NULL,
                          status                VARCHAR(20)    NOT NULL,
                          created_at            TIMESTAMPTZ    NOT NULL,
                          expires_at            TIMESTAMPTZ    NOT NULL,
                          used_at               TIMESTAMPTZ,
                          version               BIGINT         NOT NULL DEFAULT 0,

                          CONSTRAINT chk_fx_quote_status CHECK (status IN ('ACTIVE', 'USED')),
                          CONSTRAINT chk_fx_quote_send_positive CHECK (send_amount_minor > 0),
                          CONSTRAINT chk_fx_quote_fee_non_negative CHECK (fee_minor >= 0),
                          CONSTRAINT chk_fx_quote_receive_positive CHECK (receive_amount_minor > 0),
                          CONSTRAINT chk_fx_quote_margin_non_negative CHECK (margin_minor >= 0),
                          CONSTRAINT chk_fx_quote_spread CHECK (spread_bps >= 0 AND spread_bps < 10000),
                          CONSTRAINT chk_fx_quote_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_fx_quote_requester ON fx_quote (requester_id, created_at);