-- 샘플 쇼핑몰의 표. 라이브러리 표(ledger_command 등)와 같은 DB에 둔다. 앱이 기동할 때 ShopSchema가 적용한다.

-- 주문 하나. 결제 요청의 멱등 키마다 하나만 생긴다(더블 클릭, F38)
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

-- 주문이 접수한 원장 명령. 핸들러가 명령 id로 주문을 찾는다
CREATE TABLE shop_order_command (
    command_id text COLLATE "C" PRIMARY KEY,
    order_id   bigint NOT NULL REFERENCES shop_order (id),
    kind       text NOT NULL CHECK (kind IN ('RESERVE','POST','VOID'))
);
CREATE INDEX shop_order_command_order ON shop_order_command (order_id);

-- 살아 있는 앱 인스턴스와 그 인스턴스가 TB에 보낸 명령 수. 화면의 "Workers ×N" 칸이 읽는다
CREATE TABLE shop_instance (
    name            text PRIMARY KEY,
    seen_at         timestamptz NOT NULL,
    submitted_total bigint NOT NULL
);
