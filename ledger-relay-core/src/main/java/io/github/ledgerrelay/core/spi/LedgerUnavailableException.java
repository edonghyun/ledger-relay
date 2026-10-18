package io.github.ledgerrelay.core.spi;

/**
 * TB 클라이언트를 더 쓸 수 없다(닫힘, 세션 축출, 클라이언트·서버 버전 불일치). 다시 보내도 회복되지 않는다. 어댑터가 자기
 * 클라이언트의 예외를 이것으로 감싸 알린다. 워커는 일시적 실패와 따로 알리고({@link WorkerEvents#ledgerUnavailable}) 명령은
 * 되돌린다. 멈출지는 부르는 쪽(spring-boot 모듈의 루프와 health)이 정한다.
 */
public final class LedgerUnavailableException extends RuntimeException {

    public LedgerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
