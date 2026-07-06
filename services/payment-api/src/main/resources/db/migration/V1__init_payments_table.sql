CREATE TABLE payments (
    id              UUID PRIMARY KEY,
    amount          NUMERIC(19, 2) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL
);
