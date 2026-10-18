package io.github.ledgerrelay.shop.operator;

import io.github.ledgerrelay.core.AbandonOutcome;
import io.github.ledgerrelay.core.CannotAbandonException;
import io.github.ledgerrelay.core.CommandNotFoundException;
import io.github.ledgerrelay.core.HaltedCommand;
import io.github.ledgerrelay.core.LedgerRelayOperator;
import io.github.ledgerrelay.core.ReconciliationReport;
import io.github.ledgerrelay.shop.order.OrderCommand;
import io.github.ledgerrelay.shop.order.Orders;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Operator tab of the UI. Exposes the library's operator port over HTTP. The library makes every decision. */
@RestController
@RequestMapping("/api/operator")
class OperatorController {

    /** Reconciliation page size. Equal to the library limit (1000). */
    static final int PAGE = 1000;

    record HaltedView(long position, String commandId, String orderingKey, long seq, String kind, String reason,
            Instant haltedAt, Long orderId) {
    }

    record MismatchView(String commandId, String kind) {
    }

    record ReconcileView(long checked, long matched, List<MismatchView> mismatches) {
    }

    private final LedgerRelayOperator operator;
    private final Orders orders;

    OperatorController(LedgerRelayOperator operator, Orders orders) {
        this.operator = operator;
        this.orders = orders;
    }

    @GetMapping("/halted")
    List<HaltedView> halted(@RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "100") int limit) {
        return operator.haltedCommands(after, Math.clamp(limit, 1, PAGE)).stream().map(this::view).toList();
    }

    @PostMapping("/halted/{commandId}/abandon")
    Map<String, String> abandon(@PathVariable String commandId) {
        AbandonOutcome outcome = operator.abandonHalted(commandId);
        return Map.of("outcome", outcome.name());
    }

    /**
     * Reconciles from the first command to the last and returns the commands that do not match. The Consistency panel
     * at the bottom of the UI shows only the totals of the same reconciliation, every second.
     */
    @PostMapping("/reconcile")
    ReconcileView reconcile() {
        long after = 0;
        long checked = 0;
        long matched = 0;
        List<MismatchView> mismatches = new ArrayList<>();
        while (true) {
            ReconciliationReport page = operator.reconcile(after, PAGE);
            if (page.lastPosition() == after) {
                return new ReconcileView(checked, matched, mismatches);
            }
            checked += page.checked();
            matched += page.matched();
            page.mismatches().stream().limit(ReconciliationReport.MAX_MISMATCHES - mismatches.size())
                    .forEach(m -> mismatches.add(new MismatchView(m.commandId(), m.kind().name())));
            after = page.lastPosition();
        }
    }

    @ExceptionHandler(CommandNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(CommandNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CannotAbandonException.class)
    ResponseEntity<Map<String, String>> cannotAbandon(CannotAbandonException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage(), "reason", e.reason().name()));
    }

    private HaltedView view(HaltedCommand command) {
        Long orderId = orders.commandOf(command.commandId()).map(OrderCommand::orderId).orElse(null);
        return new HaltedView(command.position(), command.commandId(), command.orderingKey(), command.seq(),
                command.kind().name(), command.reason().name(), command.haltedAt(), orderId);
    }
}
