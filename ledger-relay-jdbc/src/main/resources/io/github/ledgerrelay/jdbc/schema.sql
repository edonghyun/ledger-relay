-- ledger-relay schema. The app's migration tool (Flyway, Liquibase, etc.) applies it. The library does not apply it.
-- Unsigned values: u128 is numeric(39,0), u64 is numeric(20,0), u32 is bigint, u16 is integer. CHECK enforces ranges.
-- Enum values: state, kind and skip_reason hold Java constant names; known_result and result_code hold the stored
-- name of the result code (wireName).
-- IDs (command_id, ordering_key, reservation_command_id) are opaque values compared as bytes, so they use
-- COLLATE "C". The unique indexes stay correct even if the OS collation changes (F31). Ordering key rows are never
-- deleted: deleting one would reset the sequence number to 1 under contention.

-- The next sequence number to hand out, per ordering key. Acceptance locks this row and holds it until commit, so
-- seq order equals commit order.
-- Only acceptance and the claim's head pass-on lock this row. Transactions that write the result and call the handler
-- do not.
CREATE TABLE ledger_ordering_key (
    ordering_key     text COLLATE "C" PRIMARY KEY,
    next_seq         bigint NOT NULL CHECK (next_seq >= 1)
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
    -- The transaction that inserted this row. Tells whether a command refers to a reservation accepted in the same
    -- transaction. xid8 does not wrap around.
    accepted_xact          xid8 NOT NULL DEFAULT pg_current_xact_id(),
    accepted_at            timestamptz NOT NULL DEFAULT now(),
    completed_at           timestamptz,
    -- Halted (HALTED) rows only: why and when it halted (operator port). Cleared when the command ends by abandon
    halt_reason            text CHECK (halt_reason IN ('RESERVATION_MISSING','RESERVATION_NOT_EARLIER','MALFORMED_ROW')),
    halted_at              timestamptz,
    -- Whether this command is the one whose turn it is on its key. Set on insert if the key has no head. It stays set
    -- when the command finishes; the next claim locks the key row and passes it on to the next unfinished command.
    -- After fixing the DB by hand, recompute it with rebuild-heads.sql
    is_head                boolean NOT NULL DEFAULT false,
    -- When the command becomes sendable: the next attempt time for accepted commands, the lease expiry for in-flight
    -- commands
    ready_at               timestamptz GENERATED ALWAYS AS (
        CASE WHEN state = 'CLAIMED' THEN lease_expires_at ELSE next_attempt_at END) STORED,
    UNIQUE (ordering_key, seq),
    -- If transfer ids collide, TB's exists marks another command as applied (F31)
    CONSTRAINT ledger_command_transfer_id_key UNIQUE (transfer_id),
    CONSTRAINT ledger_command_seq_check CHECK (seq >= 1),
    CONSTRAINT ledger_command_skipped_reason_check CHECK ((skip_reason IS NOT NULL) = (state = 'SKIPPED')),
    -- Only post and void refer to a reservation, and when they do, the reservation's transfer id is present too
    -- (F29: rejects such rows up front so they cannot halt the claim)
    CONSTRAINT ledger_command_reference_check CHECK (
        (kind IN ('POST','VOID')) = (reservation_command_id IS NOT NULL AND pending_transfer_id IS NOT NULL)),
    -- An in-flight row without a lease expiry would never be claimed again
    CONSTRAINT ledger_command_claimed_lease_check CHECK (state <> 'CLAIMED' OR lease_expires_at IS NOT NULL),
    -- Halted rows have a reason and a time; other rows have neither
    CONSTRAINT ledger_command_halt_check CHECK ((state = 'HALTED') = (halt_reason IS NOT NULL AND halted_at IS NOT NULL)
        AND (halt_reason IS NULL) = (halted_at IS NULL)),
    -- A result code is one of the 68 stored names (wireName). An unknown value would make the claim fail reading that
    -- row and halt everything (F29). When a TB upgrade adds codes, update this list too (everyWireNameIsAccepted in
    -- JdbcCommandStoreTest catches a mismatch, F8)
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

-- Finding the head of each key among unfinished commands. HALTED is included because it also blocks the key (F9).
CREATE INDEX ledger_command_unfinished ON ledger_command (ordering_key, seq)
    WHERE state IN ('PENDING','CLAIMED','HALTED');

-- One head per key. The DB enforces it, so a bug in the code that updates the mark cannot create two.
CREATE UNIQUE INDEX ledger_command_one_head ON ledger_command (ordering_key) WHERE is_head;

-- Picks claim candidates (lockDue) in the order they became sendable. It holds only head commands, so it reads
-- neither finished history (F19) nor the backlog behind a blocked key, and since index order is candidate order, the
-- scan stops after locking limit rows (F32).
CREATE INDEX ledger_command_ready ON ledger_command (ready_at, id)
    WHERE is_head AND state IN ('PENDING','CLAIMED');

-- Commands that are finished but still the head. The claim uses it to find keys to pass on. Rows leave it once
-- passed on, so it holds only commands that just finished.
CREATE INDEX ledger_command_finished_head ON ledger_command (id)
    WHERE is_head AND state IN ('APPLIED','REJECTED','CONFLICT','SKIPPED');

-- List of halted commands (operator port). It holds only halted commands, so it is small.
CREATE INDEX ledger_command_halted ON ledger_command (id) WHERE state = 'HALTED';

-- Pending and in-flight commands (health). The oldest waiting command is read from the first entry, and counts stop
-- at the cap. This keeps health from slowing down as the backlog grows during an outage.
CREATE INDEX ledger_command_waiting ON ledger_command (id) WHERE state IN ('PENDING','CLAIMED');
