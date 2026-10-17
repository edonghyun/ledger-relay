package io.github.ledgerrelay.core.application;

import java.util.Objects;

/** 워커 쓰기 하나의 결과. 워커는 이것만 보고 지표와 로그를 남긴다. */
public sealed interface WriteResult {

    /** 반영됐다. 최종 상태였다면 핸들러도 끝까지 실행됐다. */
    record Written() implements WriteResult {
    }

    /** 0행이다(G5). 다른 워커가 가져갔거나 이미 끝났다. 정상적인 경쟁 결과다(stale_write_rejected). */
    record Fenced() implements WriteResult {
    }

    /** 결과 반영 핸들러가 던졌다. 최종 쓰기는 없던 일이 됐고, 받은 결과를 남긴 채 backoff 뒤 다시 잡힌다(§5.4). */
    record HandlerFailed(RuntimeException cause) implements WriteResult {
        public HandlerFailed {
            Objects.requireNonNull(cause, "cause");
        }
    }
}
