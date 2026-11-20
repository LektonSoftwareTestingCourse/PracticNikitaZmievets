package com.processing.apitests.support;

/**
 * Исходы отказа: пара «доменный код + причина».
 *
 * <p>Источники: таблица кодов ответов в docs/api-spec.md («Коды ответов»),
 * services/authorization/.../constants/DeclineOutcome.java (причины и коды Authorization),
 * services/common/.../AuthorizationResponse.java (unknownBin / authUnavailable — исходы Switch).</p>
 *
 * <p>Исходы по статусу карты (CARD_BLOCKED / CARD_INACTIVE с одинаковым кодом 05, CARD_EXPIRED)
 * заданы прямо в @CsvSource параметризованных тестов; тесты всегда проверяют код и причину совместно.</p>
 */
public enum Decline {
    CARD_NOT_FOUND("14", "CARD_NOT_FOUND"),
    CARD_EXPIRED("54", "CARD_EXPIRED"),
    INSUFFICIENT_FUNDS("51", "INSUFFICIENT_FUNDS"),
    EXCEEDS_AMOUNT_LIMIT("61", "EXCEEDS_AMOUNT_LIMIT"),
    AUTHORIZATION_UNAVAILABLE("05", "Authorization service unavailable");

    private final String code;
    private final String reason;

    Decline(String code, String reason) {
        this.code = code;
        this.reason = reason;
    }

    public String code() {
        return code;
    }

    public String reason() {
        return reason;
    }
}
