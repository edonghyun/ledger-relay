-- Tables of the sample shop. They live in the same DB as the library tables (ledger_command etc.). ShopSchema applies them at startup.

-- One order. Exactly one is created per idempotency key of a payment request (double click, F38)
CREATE TABLE shop_order (
    id              bigserial PRIMARY KEY,
    idempotency_key text COLLATE "C" NOT NULL UNIQUE,
    buyer_id        bigint NOT NULL,
    product_code    text NOT NULL,
    amount_satang   bigint NOT NULL CHECK (amount_satang > 0),
    state           text NOT NULL CHECK (state IN
        ('ACCEPTED','AUTHORIZED','SHIP_REQUESTED','PAID','CANCEL_REQUESTED','CANCELLED','FAILED')),
    failure_reason  text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Ledger commands accepted for an order. The outcome handler finds the order by command id
CREATE TABLE shop_order_command (
    command_id text COLLATE "C" PRIMARY KEY,
    order_id   bigint NOT NULL REFERENCES shop_order (id),
    kind       text NOT NULL CHECK (kind IN ('RESERVE','POST','VOID'))
);
CREATE INDEX shop_order_command_order ON shop_order_command (order_id);

-- Live app instances and the number of commands each has sent to TB. The "Workers ×N" box in the UI reads it
CREATE TABLE shop_instance (
    name            text PRIMARY KEY,
    seen_at         timestamptz NOT NULL,
    submitted_total bigint NOT NULL
);
