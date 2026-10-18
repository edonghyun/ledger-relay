-- 맨 앞 표시(is_head)와 키 행을 행에서 다시 계산한다.
-- 워커를 멈추고 DB를 직접 고친 뒤(행 삭제, 상태 수정) 한 트랜잭션에서 실행한다(psql -1). 잠금은 커밋까지 유지된다.
-- 맨 앞은 min(seq)로 고른다. NOT EXISTS로 고르면 백로그가 긴 키에서 제곱으로 느려진다.
-- 키 행을 잡는 접수와 claim의 넘기기를 이 잠금이 막는다. 없으면 계산하는 동안 커밋된 접수가 스스로 맨 앞이 되어, 이 스크립트가
-- 같은 키에 맨 앞을 하나 더 켜다 유일 인덱스(ledger_command_one_head)에 걸린다.
LOCK TABLE ledger_ordering_key IN EXCLUSIVE MODE;
UPDATE ledger_command SET is_head = false WHERE is_head;
UPDATE ledger_command c SET is_head = true
FROM (SELECT ordering_key, min(seq) AS seq FROM ledger_command
      WHERE state IN ('PENDING','CLAIMED','HALTED') GROUP BY ordering_key) h
WHERE c.ordering_key = h.ordering_key AND c.seq = h.seq;
INSERT INTO ledger_ordering_key (ordering_key, next_seq)
SELECT ordering_key, max(seq) + 1 FROM ledger_command GROUP BY ordering_key
ON CONFLICT (ordering_key) DO UPDATE SET next_seq = greatest(ledger_ordering_key.next_seq, excluded.next_seq);
