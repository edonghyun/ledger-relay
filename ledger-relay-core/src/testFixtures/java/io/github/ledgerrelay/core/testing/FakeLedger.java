package io.github.ledgerrelay.core.testing;

import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.spi.LedgerClient;
import io.github.ledgerrelay.core.spi.Submission;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 워커 테스트용 가짜 TB. 정하지 않은 이체는 {@code Created}로 끝난다. 이체마다 결과를 정하거나({@link #answer}), 응답을
 * 붙잡거나({@link #holdReplies}), 다음 요청을 실패시키거나({@link #failNext}), 결과 개수를 틀리게 할 수 있다
 * ({@link #replyWithCount}). 받은 요청은 {@link #requests()}로 본다. 워커가 future 콜백에서 슬롯을 돌려주므로 스레드
 * 안전하다.
 */
public final class FakeLedger implements LedgerClient {

    private final Map<BigInteger, LedgerResultCode> answers = new HashMap<>();
    private final List<List<Submission>> requests = new ArrayList<>();
    private final List<Held> held = new ArrayList<>();
    private boolean holding;
    private RuntimeException nextFailure;
    private Integer nextCount;
    private RuntimeException nextThrow;
    private boolean nextNull;

    /** 이 이체 ID에는 이 결과를 낸다. */
    public synchronized void answer(BigInteger transferId, LedgerResultCode code) {
        answers.put(transferId, code);
    }

    /** 이후 요청의 응답을 붙잡는다. TB 클라이언트에 timeout이 없어 응답이 영영 안 오는 상황이다. */
    public synchronized void holdReplies() {
        holding = true;
    }

    /** 붙잡은 응답을 모두 끝내고, 이후 요청은 바로 답한다. */
    public void releaseHeldReplies() {
        List<Held> releasing;
        synchronized (this) {
            holding = false;
            releasing = List.copyOf(held);
            held.clear();
        }
        releasing.forEach(h -> h.reply().complete(h.results()));
    }

    /** 다음 요청 하나를 이 예외로 끝낸다. */
    public synchronized void failNext(RuntimeException cause) {
        nextFailure = cause;
    }

    /** 다음 요청 하나는 future를 돌려주지 않고 보내는 자리에서 이 예외를 던진다(닫힌 클라이언트, 배치 채우기 실패). */
    public synchronized void throwOnNextSubmit(RuntimeException failure) {
        nextThrow = failure;
    }

    /** 다음 요청 하나는 future 대신 null을 돌려준다(계약을 어긴 클라이언트). */
    public synchronized void replyWithNull() {
        nextNull = true;
    }

    /** 다음 요청 하나에 결과를 n개만 낸다. 요청보다 많으면 남는 몫은 Created다. */
    public synchronized void replyWithCount(int n) {
        nextCount = n;
    }

    public synchronized List<List<Submission>> requests() {
        return List.copyOf(requests);
    }

    @Override
    public synchronized CompletableFuture<List<LedgerResultCode>> submit(List<Submission> batch) {
        requests.add(List.copyOf(batch));
        if (nextThrow != null) {
            RuntimeException failure = nextThrow;
            nextThrow = null;
            throw failure;
        }
        if (nextNull) {
            nextNull = false;
            return null;
        }
        if (nextFailure != null) {
            RuntimeException failure = nextFailure;
            nextFailure = null;
            return CompletableFuture.failedFuture(failure);
        }
        List<LedgerResultCode> results = new ArrayList<>();
        for (Submission submission : batch) {
            results.add(answers.getOrDefault(submission.transferId(), LedgerResultCode.Created));
        }
        if (nextCount != null) {
            results = new ArrayList<>(results.subList(0, Math.min(nextCount, results.size())));
            while (results.size() < nextCount) {
                results.add(LedgerResultCode.Created);
            }
            nextCount = null;
        }
        CompletableFuture<List<LedgerResultCode>> reply = new CompletableFuture<>();
        if (holding) {
            held.add(new Held(reply, List.copyOf(results)));
        } else {
            reply.complete(List.copyOf(results));
        }
        return reply;
    }

    private record Held(CompletableFuture<List<LedgerResultCode>> reply, List<LedgerResultCode> results) {
    }
}
