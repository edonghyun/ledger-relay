-- 맨 앞 표시(is_head)와 미완료 개수(unfinished_count)를 행에서 다시 계산한다(02e P2e-5).
-- 워커를 멈추고 DB를 직접 고친 뒤(행 삭제, 상태 수정) 한 트랜잭션에서 실행한다(psql -1). 잠금은 커밋까지 유지된다.
-- 맨 앞은 min(seq)로 고른다. NOT EXISTS로 고르면 백로그가 긴 키에서 제곱으로 느려진다.
-- 접수와 완료는 모두 키 행을 고치므로, 이 잠금이 둘을 막는다. 없으면 계산 중에 커밋된 접수를 옛 스냅숏의 개수로 덮어써
-- 그 키의 마지막 완료가 CHECK(unfinished_count >= 0)에 걸려 계속 실패한다.
LOCK TABLE ledger_ordering_key IN EXCLUSIVE MODE;
UPDATE ledger_command SET is_head = false WHERE is_head;
UPDATE ledger_command c SET is_head = true
FROM (SELECT ordering_key, min(seq) AS seq FROM ledger_command
      WHERE state IN ('PENDING','CLAIMED','HALTED') GROUP BY ordering_key) h
WHERE c.ordering_key = h.ordering_key AND c.seq = h.seq;
INSERT INTO ledger_ordering_key (ordering_key, next_seq, unfinished_count)
SELECT ordering_key, max(seq) + 1, count(*) FILTER (WHERE state IN ('PENDING','CLAIMED','HALTED'))
FROM ledger_command GROUP BY ordering_key
ON CONFLICT (ordering_key) DO UPDATE
    SET unfinished_count = excluded.unfinished_count,
        next_seq = greatest(ledger_ordering_key.next_seq, excluded.next_seq);
UPDATE ledger_ordering_key k SET unfinished_count = 0
WHERE unfinished_count <> 0 AND NOT EXISTS (SELECT 1 FROM ledger_command c
    WHERE c.ordering_key = k.ordering_key AND c.state IN ('PENDING','CLAIMED','HALTED'));
