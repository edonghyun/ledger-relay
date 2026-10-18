package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.spi.LedgerClient;
import io.github.ledgerrelay.core.spi.LedgerUnavailableException;
import io.github.ledgerrelay.core.spi.Submission;
import io.github.ledgerrelay.core.spi.WorkerEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 워커 한 바퀴(스펙 §5.3, 03a P3a-1·P3a-4). 스레드·주기·종료는 부르는 쪽(03b)이 맡는다. 여러 스레드가 같은 인스턴스의
 * {@link #runOnce()}를 동시에 불러도 된다. 바퀴끼리 나누는 상태는 in-flight 슬롯뿐이다.
 *
 * <p>한 바퀴: 슬롯을 잡는다 → claim → 멈춘 명령을 알린다 → 건너뛸 명령과 결과를 아는 명령은 TB 없이 끝낸다 → 나머지는
 * 제출을 기록하고 한 요청으로 보낸다 → 명령마다 따로 결과를 쓴다.
 */
public final class RelayWorker {

    private final CommandLifecycle lifecycle;
    private final LedgerClient ledger;
    private final InFlightLimit slots;
    private final WorkerSettings settings;
    private final WorkerEvents events;

    public RelayWorker(CommandLifecycle lifecycle, LedgerClient ledger, InFlightLimit slots, WorkerSettings settings,
            WorkerEvents events) {
        if (slots.capacity() != settings.maxInFlight()) {
            // 설정의 maxInFlight가 실제 상한이어야 한다. 다르면 설정을 바꿔도 동시 요청 수가 바뀌지 않는다(02d I5)
            throw new IllegalArgumentException("in-flight 슬롯(" + slots.capacity() + ")이 설정의 maxInFlight("
                    + settings.maxInFlight() + ")와 다릅니다. 같은 값으로 만드세요");
        }
        this.lifecycle = lifecycle;
        this.ledger = ledger;
        this.slots = slots;
        this.settings = settings;
        this.events = events;
    }

    public CycleReport runOnce() {
        // claim 전에 슬롯을 잡는다. claim 뒤에 기다리면 lease를 쥔 채 막혀 다른 워커가 같은 명령을 다시 잡는다(P3a-2)
        if (!slots.tryTake(settings.batchSize(), settings.pollTimeout())) {
            events.noSlots();
            return CycleReport.IDLE;
        }
        int held = settings.batchSize();
        try {
            ClaimBatch batch = lifecycle.claim(settings.batchSize(), settings.lease());
            batch.halted().forEach(events::halted);
            batch.problems().forEach(problem -> events.malformedCandidate(problem.commandId(), problem.cause()));
            if (batch.claimed().isEmpty()) {
                return CycleReport.IDLE;
            }
            int completed = 0;
            List<ClaimedCommand> sending = new ArrayList<>();
            for (ClaimedCommand claimed : batch.claimed()) {
                if (claimed.skip()) {
                    completed += attempt(claimed, () -> lifecycle.skip(claimed));
                } else if (claimed.knownResult().isPresent()) {
                    completed += attempt(claimed, () -> lifecycle.complete(claimed, claimed.knownResult().get()));
                } else if (attempt(claimed, () -> lifecycle.markSubmitted(claimed)) == 1) {
                    sending.add(claimed);
                }
            }
            if (sending.isEmpty()) {
                return new CycleReport(batch.claimed().size(), 0, completed, 0);
            }
            List<Submission> request = sending.stream().map(ClaimedCommand::submission).toList();
            int sent = request.size();
            CompletableFuture<List<LedgerResultCode>> reply;
            try {
                reply = ledger.submit(request);
                if (reply == null) {
                    // 계약을 어긴 클라이언트다. 슬롯 반납을 걸 future가 없으므로 보내지 못한 것으로 본다(02d I7)
                    throw new IllegalStateException("LedgerClient.submit이 null을 돌려줬다");
                }
            } catch (RuntimeException e) {
                failed(sent, e);
                return released(batch, sent, completed, sending);
            }
            // 보낸 몫의 슬롯은 요청이 실제로 끝날 때 돌아온다. timeout이 나도 요청은 TB 클라이언트 안에 남는다(§5.3-2)
            slots.giveBack(held - sent);
            held = 0;
            reply.whenComplete((results, failure) -> slots.giveBack(sent));
            events.submitted(sent);
            List<LedgerResultCode> results;
            try {
                results = reply.get(settings.submitTimeout().toNanos(), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // 늦게 오는 응답은 무시한다. future에는 슬롯 반납만 걸려 있다(P3a-5)
                events.submitTimedOut(sent);
                return released(batch, sent, completed, sending);
            } catch (ExecutionException e) {
                failed(sent, e.getCause());
                return released(batch, sent, completed, sending);
            } catch (InterruptedException e) {
                CycleReport report = released(batch, sent, completed, sending);
                Thread.currentThread().interrupt();
                return report;
            }
            if (results.size() != sent) {
                // 결과를 순서로 짝지으므로 하나만 밀려도 남의 결과로 끝난다. 쓰지 않는다(P3a-6)
                events.malformedReply(sent, results.size());
                return released(batch, sent, completed, sending);
            }
            for (int i = 0; i < sending.size(); i++) {
                ClaimedCommand claimed = sending.get(i);
                LedgerResultCode code = results.get(i);
                // 명령마다 따로 쓴다. 하나가 실패해도 나머지가 받은 결과를 잃지 않게(§5.3-3)
                completed += attempt(claimed, () -> lifecycle.complete(claimed, code));
            }
            return new CycleReport(batch.claimed().size(), sent, completed, 0);
        } finally {
            slots.giveBack(held);
        }
    }

    /** 회복할 수 없는 클라이언트 실패는 일시적 실패와 따로 알린다(P2d-8). */
    private void failed(int sent, Throwable cause) {
        if (cause instanceof LedgerUnavailableException) {
            events.ledgerUnavailable(cause);
        } else {
            events.submitFailed(sent, cause);
        }
    }

    /** 보낸 명령을 모두 되돌린다. backoff가 지나면 같은 이체 ID로 다시 잡힌다(§5.3-4, D7). */
    private CycleReport released(ClaimBatch batch, int sent, int completed, List<ClaimedCommand> sending) {
        int released = 0;
        for (ClaimedCommand claimed : sending) {
            released += attempt(claimed, () -> lifecycle.release(claimed));
        }
        return new CycleReport(batch.claimed().size(), sent, completed, released);
    }

    /**
     * 쓰기 하나를 한다. 예외로 끝나면 그 명령만 알리고 0이다. 그 명령은 lease가 끝날 때까지 처리 중으로 남고 같은 이체
     * ID로 다시 잡힌다. 같은 바퀴의 다른 명령은 계속 쓴다. Error는 삼키지 않는다.
     *
     * @return 반영됐으면 1. 0행, 핸들러 실패, 쓰기 실패는 알리고 0이다
     */
    private int attempt(ClaimedCommand claimed, Supplier<WriteResult> write) {
        WriteResult result;
        try {
            result = write.get();
        } catch (RuntimeException e) {
            events.writeFailed(claimed.commandId(), e);
            return 0;
        }
        return switch (result) {
            case WriteResult.Written written -> 1;
            case WriteResult.Fenced fenced -> {
                events.fenced(claimed.commandId());
                yield 0;
            }
            case WriteResult.HandlerFailed failed -> {
                events.handlerFailed(claimed.commandId(), failed.cause());
                yield 0;
            }
        };
    }
}
