package io.github.ledgerrelay.core;

import java.util.Objects;

/** 거절·충돌로 끝난 명령의 사유({@code rejection() = {category, tbCode}}). 분류는 코드가 정한다. */
public record Rejection(ResultCategory category, LedgerResultCode tbCode) {

    public Rejection {
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(tbCode, "tbCode");
        if (category == ResultCategory.APPLIED) {
            throw new IllegalArgumentException("적용된 결과(" + tbCode + ")는 거절 사유가 아닙니다");
        }
        if (tbCode.category() != category) {
            throw new IllegalArgumentException(tbCode + "의 분류는 " + tbCode.category() + "입니다. " + category + "가 아닙니다");
        }
    }

    public static Rejection of(LedgerResultCode tbCode) {
        return new Rejection(tbCode.category(), tbCode);
    }
}
