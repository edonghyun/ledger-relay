-- ledger-relay 스키마(스펙 §5.6). 앱의 마이그레이션 도구(Flyway, Liquibase 등)가 적용한다. 라이브러리는 자동 적용하지 않는다.
-- 부호 없는 값(P2b-5): u128은 numeric(39,0), u64는 numeric(20,0), u32는 bigint, u16은 integer. 범위는 CHECK가 막는다.
-- 열거값(P2b-6): state·kind·skip_reason은 Java 상수 이름, known_result·result_code는 결과 코드의 저장 이름(wireName).
-- ID(command_id, ordering_key, reservation_command_id)는 바이트로 비교하는 불투명 값이라 COLLATE "C"다. OS collation이 바뀌어도
-- 유일 인덱스가 어긋나지 않는다(F31). 순서 키 행은 지우지 않는다: 지우면 경쟁 중에 순번이 1로 돌아간다(02d P2d-11).

-- 순서 키마다 다음에 낼 순번. 접수가 이 행을 잠그고 커밋까지 놓지 않으므로 seq 순서가 커밋 순서와 같다(R12, P2b-8).
-- 미완료 개수는 접수와 완료가 이 행을 함께 고치게 하려고 둔다. 이 갱신이 같은 키의 접수·완료를 줄 세우므로 맨 앞 표시가
-- 사라지지 않는다(02e P2e-4).
CREATE TABLE ledger_ordering_key (
    ordering_key     text COLLATE "C" PRIMARY KEY,
    next_seq         bigint NOT NULL CHECK (next_seq >= 1),
    unfinished_count bigint NOT NULL DEFAULT 0 CHECK (unfinished_count >= 0)
);

CREATE TABLE ledger_command (
    id                     bigserial PRIMARY KEY,
    command_id             text COLLATE "C" NOT NULL UNIQUE,
    transfer_id            numeric(39,0) NOT NULL CHECK (transfer_id BETWEEN 0 AND 340282366920938463463374607431768211455),
    ordering_key           text COLLATE "C" NOT NULL,
    seq                    bigint NOT NULL,
    kind                   text NOT NULL CHECK (kind IN ('TRANSFER','RESERVE','POST','VOID')),
    reservation_command_id text COLLATE "C",
    pending_transfer_id    numeric(39,0) CHECK (pending_transfer_id BETWEEN 0 AND 340282366920938463463374607431768211455),
    debit_account_id       numeric(39,0) NOT NULL CHECK (debit_account_id BETWEEN 0 AND 340282366920938463463374607431768211455),
    credit_account_id      numeric(39,0) NOT NULL CHECK (credit_account_id BETWEEN 0 AND 340282366920938463463374607431768211455),
    amount                 numeric(39,0) NOT NULL CHECK (amount BETWEEN 0 AND 340282366920938463463374607431768211455),
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
    -- 그 키에서 가장 앞 순번의 미완료 명령인가. 저장소가 넣기와 완료 때 고친다(02e P2e-4). DB를 직접 고친 뒤에는
    -- rebuild-heads.sql로 다시 계산한다(P2e-5)
    is_head                boolean NOT NULL DEFAULT false,
    -- 보낼 수 있게 되는 시각: 접수 명령은 다음 시도 시각, 처리 중 명령은 lease 만료 시각(02e P2e-1)
    ready_at               timestamptz GENERATED ALWAYS AS (
        CASE WHEN state = 'CLAIMED' THEN lease_expires_at ELSE next_attempt_at END) STORED,
    UNIQUE (ordering_key, seq),
    -- 이체 ID가 겹치면 TB의 exists가 남의 명령을 적용됨으로 만든다(F31)
    CONSTRAINT ledger_command_transfer_id_key UNIQUE (transfer_id),
    CONSTRAINT ledger_command_seq_check CHECK (seq >= 1),
    CONSTRAINT ledger_command_skipped_reason_check CHECK ((skip_reason IS NOT NULL) = (state = 'SKIPPED')),
    -- post·void만 예약을 참조하고, 참조하면 예약의 이체 ID도 있다(F29: 이런 행이 claim을 멈추지 않게 애초에 막는다)
    CONSTRAINT ledger_command_reference_check CHECK (
        (kind IN ('POST','VOID')) = (reservation_command_id IS NOT NULL AND pending_transfer_id IS NOT NULL)),
    -- 처리 중인데 lease 만료 시각이 없으면 영원히 다시 잡히지 않는다
    CONSTRAINT ledger_command_claimed_lease_check CHECK (state <> 'CLAIMED' OR lease_expires_at IS NOT NULL),
    -- 결과 코드는 저장 이름(wireName) 68개 중 하나다. 모르는 값이 있으면 claim이 그 행을 읽다 전체가 멈춘다(F29).
    -- TB를 올려 코드가 늘면 이 목록도 함께 고친다(JdbcCommandStoreTest의 everyWireNameIsAccepted가 어긋남을 잡는다, F8)
    CONSTRAINT ledger_command_known_result_check CHECK (known_result IN (
        'created', 'linked_event_failed', 'linked_event_chain_open', 'imported_event_expected',
        'imported_event_not_expected', 'timestamp_must_be_zero', 'imported_event_timestamp_out_of_range',
        'imported_event_timestamp_must_not_advance', 'reserved_flag', 'id_must_not_be_zero',
        'id_must_not_be_int_max', 'exists_with_different_flags', 'exists_with_different_pending_id',
        'exists_with_different_timeout', 'exists_with_different_debit_account_id',
        'exists_with_different_credit_account_id', 'exists_with_different_amount',
        'exists_with_different_user_data_128', 'exists_with_different_user_data_64',
        'exists_with_different_user_data_32', 'exists_with_different_ledger', 'exists_with_different_code', 'exists',
        'id_already_failed', 'flags_are_mutually_exclusive', 'debit_account_id_must_not_be_zero',
        'debit_account_id_must_not_be_int_max', 'credit_account_id_must_not_be_zero',
        'credit_account_id_must_not_be_int_max', 'accounts_must_be_different', 'pending_id_must_be_zero',
        'pending_id_must_not_be_zero', 'pending_id_must_not_be_int_max', 'pending_id_must_be_different',
        'timeout_reserved_for_pending_transfer', 'closing_transfer_must_be_pending', 'ledger_must_not_be_zero',
        'code_must_not_be_zero', 'debit_account_not_found', 'credit_account_not_found',
        'accounts_must_have_the_same_ledger', 'transfer_must_have_the_same_ledger_as_accounts',
        'pending_transfer_not_found', 'pending_transfer_not_pending',
        'pending_transfer_has_different_debit_account_id', 'pending_transfer_has_different_credit_account_id',
        'pending_transfer_has_different_ledger', 'pending_transfer_has_different_code',
        'exceeds_pending_transfer_amount', 'pending_transfer_has_different_amount',
        'pending_transfer_already_posted', 'pending_transfer_already_voided', 'pending_transfer_expired',
        'imported_event_timestamp_must_not_regress', 'imported_event_timestamp_must_postdate_debit_account',
        'imported_event_timestamp_must_postdate_credit_account', 'imported_event_timeout_must_be_zero',
        'debit_account_already_closed', 'credit_account_already_closed', 'overflows_debits_pending',
        'overflows_credits_pending', 'overflows_debits_posted', 'overflows_credits_posted', 'overflows_debits',
        'overflows_credits', 'overflows_timeout', 'exceeds_credits', 'exceeds_debits')),
    CONSTRAINT ledger_command_result_code_check CHECK (result_code IN (
        'created', 'linked_event_failed', 'linked_event_chain_open', 'imported_event_expected',
        'imported_event_not_expected', 'timestamp_must_be_zero', 'imported_event_timestamp_out_of_range',
        'imported_event_timestamp_must_not_advance', 'reserved_flag', 'id_must_not_be_zero',
        'id_must_not_be_int_max', 'exists_with_different_flags', 'exists_with_different_pending_id',
        'exists_with_different_timeout', 'exists_with_different_debit_account_id',
        'exists_with_different_credit_account_id', 'exists_with_different_amount',
        'exists_with_different_user_data_128', 'exists_with_different_user_data_64',
        'exists_with_different_user_data_32', 'exists_with_different_ledger', 'exists_with_different_code', 'exists',
        'id_already_failed', 'flags_are_mutually_exclusive', 'debit_account_id_must_not_be_zero',
        'debit_account_id_must_not_be_int_max', 'credit_account_id_must_not_be_zero',
        'credit_account_id_must_not_be_int_max', 'accounts_must_be_different', 'pending_id_must_be_zero',
        'pending_id_must_not_be_zero', 'pending_id_must_not_be_int_max', 'pending_id_must_be_different',
        'timeout_reserved_for_pending_transfer', 'closing_transfer_must_be_pending', 'ledger_must_not_be_zero',
        'code_must_not_be_zero', 'debit_account_not_found', 'credit_account_not_found',
        'accounts_must_have_the_same_ledger', 'transfer_must_have_the_same_ledger_as_accounts',
        'pending_transfer_not_found', 'pending_transfer_not_pending',
        'pending_transfer_has_different_debit_account_id', 'pending_transfer_has_different_credit_account_id',
        'pending_transfer_has_different_ledger', 'pending_transfer_has_different_code',
        'exceeds_pending_transfer_amount', 'pending_transfer_has_different_amount',
        'pending_transfer_already_posted', 'pending_transfer_already_voided', 'pending_transfer_expired',
        'imported_event_timestamp_must_not_regress', 'imported_event_timestamp_must_postdate_debit_account',
        'imported_event_timestamp_must_postdate_credit_account', 'imported_event_timeout_must_be_zero',
        'debit_account_already_closed', 'credit_account_already_closed', 'overflows_debits_pending',
        'overflows_credits_pending', 'overflows_debits_posted', 'overflows_credits_posted', 'overflows_debits',
        'overflows_credits', 'overflows_timeout', 'exceeds_credits', 'exceeds_debits'))
);

-- 미완료 명령의 키별 맨 앞 판정(§5.2, R7). HALTED도 키를 막으므로 넣는다(F9).
CREATE INDEX ledger_command_unfinished ON ledger_command (ordering_key, seq)
    WHERE state IN ('PENDING','CLAIMED','HALTED');

-- 키마다 맨 앞은 하나다. 표시를 고치는 쪽에 버그가 있어도 둘이 되지 않게 DB가 막는다(02e P2e-3).
CREATE UNIQUE INDEX ledger_command_one_head ON ledger_command (ordering_key) WHERE is_head;

-- claim 후보를 접수 순서(id)로 고르는 질의(§5.2, lockDue)가 끝난 이력을 훑지 않게 한다. 없으면 플래너가 기본 키 순서로
-- 끝난 행을 하나씩 걸러 내며 읽어, 폴링 비용이 이력 크기에 비례한다(F19).
CREATE INDEX ledger_command_due ON ledger_command (id)
    WHERE state IN ('PENDING','CLAIMED');
