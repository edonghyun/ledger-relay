-- ledger-relay 스키마(스펙 §5.6). 앱의 마이그레이션 도구(Flyway, Liquibase 등)가 적용한다. 라이브러리는 자동 적용하지 않는다.
-- 부호 없는 값(P2b-5): u128은 numeric(39,0), u64는 numeric(20,0), u32는 bigint, u16은 integer. 범위는 CHECK가 막는다.
-- 열거값(P2b-6): state·kind·skip_reason은 Java 상수 이름, known_result·result_code는 결과 코드의 저장 이름(wireName).

-- 순서 키마다 다음에 낼 순번. 접수가 이 행을 잠그고 커밋까지 놓지 않으므로 seq 순서가 커밋 순서와 같다(R12, P2b-8).
CREATE TABLE ledger_ordering_key (
    ordering_key text PRIMARY KEY,
    next_seq     bigint NOT NULL CHECK (next_seq >= 1)
);

CREATE TABLE ledger_command (
    id                     bigserial PRIMARY KEY,
    command_id             text NOT NULL UNIQUE,
    transfer_id            numeric(39,0) NOT NULL,
    ordering_key           text NOT NULL,
    seq                    bigint NOT NULL,
    kind                   text NOT NULL CHECK (kind IN ('TRANSFER','RESERVE','POST','VOID')),
    reservation_command_id text,
    pending_transfer_id    numeric(39,0),
    debit_account_id       numeric(39,0) NOT NULL,
    credit_account_id      numeric(39,0) NOT NULL,
    amount                 numeric(39,0) NOT NULL,
    ledger                 bigint  NOT NULL CHECK (ledger BETWEEN 0 AND 4294967295),
    code                   integer NOT NULL CHECK (code BETWEEN 0 AND 65535),
    timeout_seconds        bigint  NOT NULL CHECK (timeout_seconds BETWEEN 0 AND 4294967295),
    user_data_64           numeric(20,0) NOT NULL CHECK (user_data_64 BETWEEN 0 AND 18446744073709551615),
    user_data_32           bigint  NOT NULL CHECK (user_data_32 BETWEEN 0 AND 4294967295),
    content_hash           text NOT NULL,
    content_hash_version   text NOT NULL DEFAULT 'v1' CHECK (content_hash_version = 'v1'),
    state                  text NOT NULL CHECK (state IN ('PENDING','CLAIMED','APPLIED','REJECTED','CONFLICT','SKIPPED','HALTED')),
    lease_token            bigint NOT NULL DEFAULT 0,
    lease_expires_at       timestamptz,
    next_attempt_at        timestamptz NOT NULL DEFAULT now(),
    submit_count           bigint NOT NULL DEFAULT 0,
    first_submitted_at     timestamptz,
    known_result           text,
    result_code            text,
    handler_failures       integer NOT NULL DEFAULT 0,
    skip_reason            text CHECK (skip_reason IN ('RESERVATION_NOT_APPLIED','ABANDONED_BY_OPERATOR')),
    -- 이 행을 넣은 트랜잭션. 같은 트랜잭션에서 접수한 예약을 참조하는지 가린다(P2b-2). xid8은 랩어라운드가 없다.
    accepted_xact          xid8 NOT NULL DEFAULT pg_current_xact_id(),
    accepted_at            timestamptz NOT NULL DEFAULT now(),
    completed_at           timestamptz,
    UNIQUE (ordering_key, seq),
    CHECK ((skip_reason IS NOT NULL) = (state = 'SKIPPED'))
);

-- 미완료 명령의 키별 맨 앞 판정(§5.2, R7). HALTED도 키를 막으므로 넣는다(F9).
CREATE INDEX ledger_command_unfinished ON ledger_command (ordering_key, seq)
    WHERE state IN ('PENDING','CLAIMED','HALTED');

-- claim 후보를 접수 순서(id)로 고르는 질의(§5.2, lockDue)가 끝난 이력을 훑지 않게 한다. 없으면 플래너가 기본 키 순서로
-- 끝난 행을 하나씩 걸러 내며 읽어, 폴링 비용이 이력 크기에 비례한다(F19).
CREATE INDEX ledger_command_due ON ledger_command (id)
    WHERE state IN ('PENDING','CLAIMED');
