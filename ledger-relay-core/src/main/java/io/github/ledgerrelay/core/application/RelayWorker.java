package io.github.ledgerrelay.core.application;

import io.github.ledgerrelay.core.LedgerResultCode;
import io.github.ledgerrelay.core.spi.LedgerClient;
import io.github.ledgerrelay.core.spi.Submission;
import io.github.ledgerrelay.core.spi.WorkerEvents;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
            if (batch.claimed().isEmpty()) {
                return CycleReport.IDLE;
            }
            int completed = 0;
            List<ClaimedCommand> sending = new ArrayList<>();
            for (ClaimedCommand claimed : batch.claimed()) {
                if (claimed.skip()) {
                    completed += written(claimed, lifecycle.skip(claimed));
                } else if (claimed.knownResult().isPresent()) {
                    completed += written(claimed, lifecycle.complete(claimed, claimed.knownResult().get()));
                } else if (written(claimed, lifecycle.markSubmitted(claimed)) == 1) {
                    sending.add(claimed);
                }
            }
            if (sending.isEmpty()) {
                return new CycleReport(batch.claimed().size(), 0, completed, 0);
            }
            List<Submission> request = sending.stream().map(ClaimedCommand::submission).toList();
            CompletableFuture<List<LedgerResultCode>> reply = ledger.submit(request);
            // 보낸 몫의 슬롯은 요청이 실제로 끝날 때 돌아온다. timeout이 나도 요청은 TB 클라이언트 안에 남는다(§5.3-2)
            int sent = request.size();
            slots.giveBack(held - sent);
            held = 0;
            reply.whenComplete((results, failure) -> slots.giveBack(sent));
            events.submitted(sent);
            List<LedgerResultCode> results = await(reply);
            for (int i = 0; i < sending.size(); i++) {
                completed += written(sending.get(i), lifecycle.complete(sending.get(i), results.get(i)));
            }
            return new CycleReport(batch.claimed().size(), sent, completed, 0);
        } finally {
            slots.giveBack(held);
        }
    }

    private List<LedgerResultCode> await(CompletableFuture<List<LedgerResultCode>> reply) {
        try {
            return reply.get(settings.submitTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return 반영됐으면 1. 0행이나 핸들러 실패는 알리고 0이다 */
    private int written(ClaimedCommand claimed, WriteResult result) {
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
