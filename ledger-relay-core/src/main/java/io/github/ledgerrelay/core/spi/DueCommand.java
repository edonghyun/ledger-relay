package io.github.ledgerrelay.core.spi;

import io.github.ledgerrelay.core.domain.QueuedCommand;
import java.util.Objects;
import java.util.Optional;

/**
 * claim 후보 하나와, 게이트가 그 후보에 대해 볼 것(§5.2).
 *
 * @param reservation post·void가 참조한 예약의 순번과 상태. 참조가 없거나 예약 행이 없으면 비어 있다
 */
public record DueCommand(CommandRecord command, Optional<QueuedCommand> reservation) {

    public DueCommand {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(reservation, "reservation");
    }
}
