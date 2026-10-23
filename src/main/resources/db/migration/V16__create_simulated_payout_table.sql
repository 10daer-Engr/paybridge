-- Only used when paybridge.provider.mode=simulated.
CREATE TABLE simulated_payout (
                                  reference     VARCHAR(60) PRIMARY KEY,
                                  scenario      VARCHAR(30) NOT NULL,
                                  amount_minor  BIGINT      NOT NULL,
                                  created_at    TIMESTAMPTZ NOT NULL
);