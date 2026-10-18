-- Recomputes the head marks (is_head) and the key rows from the command rows.
-- Run it in one transaction (psql -1) after stopping the workers and fixing the DB by hand (deleting rows, changing
-- states). The lock is held until commit.
-- The head is picked with min(seq). Picking it with NOT EXISTS gets quadratically slower on keys with a long backlog.
-- This lock blocks acceptance and the claim's pass-on, which both lock key rows. Without it, an acceptance committed
-- during the computation makes itself the head, and this script then sets a second head on the same key and fails on
-- the unique index (ledger_command_one_head).
LOCK TABLE ledger_ordering_key IN EXCLUSIVE MODE;
UPDATE ledger_command SET is_head = false WHERE is_head;
UPDATE ledger_command c SET is_head = true
FROM (SELECT ordering_key, min(seq) AS seq FROM ledger_command
      WHERE state IN ('PENDING','CLAIMED','HALTED') GROUP BY ordering_key) h
WHERE c.ordering_key = h.ordering_key AND c.seq = h.seq;
INSERT INTO ledger_ordering_key (ordering_key, next_seq)
SELECT ordering_key, max(seq) + 1 FROM ledger_command GROUP BY ordering_key
ON CONFLICT (ordering_key) DO UPDATE SET next_seq = greatest(ledger_ordering_key.next_seq, excluded.next_seq);
