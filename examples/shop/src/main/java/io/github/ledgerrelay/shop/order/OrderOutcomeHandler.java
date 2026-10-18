package io.github.ledgerrelay.shop.order;

import io.github.ledgerrelay.core.CommandKind;
import io.github.ledgerrelay.core.CommandStatus;
import io.github.ledgerrelay.core.CommandStatus.State;
import io.github.ledgerrelay.core.LedgerOutcomeHandler;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 원장 명령이 끝나면 주문에 반영한다. 라이브러리가 명령의 결과를 쓰는 트랜잭션 안에서 부른다. 이 메서드가 던지면 결과 쓰기도
 * 롤백되고 라이브러리가 backoff 뒤 다시 부른다.
 *
 * <p>주문 행을 먼저 잠그고 고친 뒤에만 접수한다(F33). 결제·출고·취소 요청도 주문 행을 먼저 잡으므로 순서가 같다.
 *
 * <table>
 *   <caption>명령 결과 → 주문</caption>
 *   <tr><th>명령</th><th>결과</th><th>주문</th></tr>
 *   <tr><td>예약</td><td>적용</td><td>접수됨 → 결제 승인됨. 취소 요청이면 그대로(해제가 뒤따른다)</td></tr>
 *   <tr><td>예약</td><td>거절·충돌</td><td>접수됨 → 실패(사유). 취소 요청 → 취소됨(묶인 돈이 없다)</td></tr>
 *   <tr><td>확정</td><td>적용</td><td>출고 요청 → 결제 완료</td></tr>
 *   <tr><td>확정</td><td>거절·충돌·건너뜀</td><td>예약을 쥐고 있으면 취소 요청으로 고치고 해제를 접수한다</td></tr>
 *   <tr><td>해제</td><td>적용·건너뜀</td><td>취소됨</td></tr>
 *   <tr><td>해제</td><td>거절·충돌</td><td>실패(사유). 운영자가 볼 일이다</td></tr>
 * </table>
 */
@Component
class OrderOutcomeHandler implements LedgerOutcomeHandler {

    private static final Log log = LogFactory.getLog(OrderOutcomeHandler.class);

    private final Orders orders;
    /** 접수 포트는 이 핸들러를 받아 만들어지므로 나중에 꺼낸다. */
    private final ObjectProvider<OrderService> service;

    OrderOutcomeHandler(Orders orders, ObjectProvider<OrderService> service) {
        this.orders = orders;
        this.service = service;
    }

    @Override
    public void onCompleted(CommandStatus outcome) {
        Optional<OrderCommand> command = orders.commandOf(outcome.commandId());
        if (command.isEmpty()) {
            log.warn("이 앱이 접수하지 않은 명령의 결과다. 무시한다: " + outcome.commandId());
            return;
        }
        long orderId = command.get().orderId();
        ShopOrder order = orders.lock(orderId).orElseThrow();
        switch (command.get().kind()) {
            case RESERVE -> onReservation(order, outcome);
            case POST -> onCapture(order, outcome);
            case VOID -> onRelease(order, outcome);
            case TRANSFER -> throw new IllegalStateException("주문은 단순 이체를 접수하지 않는다: " + outcome.commandId());
        }
    }

    private void onReservation(ShopOrder order, CommandStatus outcome) {
        boolean cancelling = order.state() == OrderState.CANCEL_REQUESTED;
        if (outcome.state() == State.APPLIED) {
            if (order.state() == OrderState.ACCEPTED) {
                orders.changeState(order.id(), OrderState.AUTHORIZED, null);
            }
        } else if (cancelling) {
            orders.changeState(order.id(), OrderState.CANCELLED, null);
        } else if (order.state() == OrderState.ACCEPTED) {
            orders.changeState(order.id(), OrderState.FAILED, reasonOf(outcome));
        }
    }

    private void onCapture(ShopOrder order, CommandStatus outcome) {
        if (outcome.state() == State.APPLIED) {
            orders.changeState(order.id(), OrderState.PAID, null);
        } else if (holdsReservation(order)) {
            // 확정이 끝내 나가지 못했다. 묶인 돈을 풀어야 주문이 끝난다(장면 3: 운영자 포기 → 취소됨)
            service.getObject().release(order);
        }
    }

    private void onRelease(ShopOrder order, CommandStatus outcome) {
        if (outcome.state() == State.APPLIED || outcome.state() == State.SKIPPED) {
            // 건너뜀은 예약이 적용되지 않았다는 뜻이다. 묶인 돈이 없으니 취소가 끝났다
            if (order.state() != OrderState.CANCELLED) {
                orders.changeState(order.id(), OrderState.CANCELLED, null);
            }
        } else {
            orders.changeState(order.id(), OrderState.FAILED, reasonOf(outcome));
        }
    }

    /** 해제를 아직 접수하지 않았고 주문이 끝나지 않았다. */
    private boolean holdsReservation(ShopOrder order) {
        boolean released = orders.commandsOf(order.id()).stream().anyMatch(command -> command.kind() == CommandKind.VOID);
        return !released && order.state() != OrderState.FAILED && order.state() != OrderState.CANCELLED;
    }

    private static String reasonOf(CommandStatus outcome) {
        return outcome.rejection().map(rejection -> rejection.tbCode().wireName())
                .or(() -> outcome.skipReason().map(Enum::name)).orElse(outcome.state().name());
    }
}
