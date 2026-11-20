package com.processing.apitests.support.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Билдер тела POST /api/cards. Значения по умолчанию — пример из docs/api-spec.md §2,
 * но баланс и лимиты каждый тест задаёт явно: известное начальное состояние карты.
 *
 * <p>Тело собирается как Map, чтобы можно было удалить поле или подставить значение
 * неверного типа — это нужно для негативных тестов валидации.</p>
 */
public final class CardRequestBuilder {

    public static final String DEFAULT_BIN = "400000";
    public static final long DEFAULT_DAILY_LIMIT = 15_000_000L;
    public static final long DEFAULT_MONTHLY_LIMIT = 300_000_000L;
    public static final long DEFAULT_BALANCE = 100_000_000L;

    private final Map<String, Object> body = new LinkedHashMap<>();

    private CardRequestBuilder() {
        body.put("bin", DEFAULT_BIN);
        body.put("cardholderName", "API TEST");
        body.put("currencyCode", "643");
        body.put("dailyLimit", DEFAULT_DAILY_LIMIT);
        body.put("monthlyLimit", DEFAULT_MONTHLY_LIMIT);
        body.put("initialBalance", DEFAULT_BALANCE);
    }

    public static CardRequestBuilder aCard() {
        return new CardRequestBuilder();
    }

    public CardRequestBuilder bin(String bin) {
        return with("bin", bin);
    }

    public CardRequestBuilder balance(long balance) {
        return with("initialBalance", balance);
    }

    public CardRequestBuilder dailyLimit(long dailyLimit) {
        return with("dailyLimit", dailyLimit);
    }

    public CardRequestBuilder monthlyLimit(long monthlyLimit) {
        return with("monthlyLimit", monthlyLimit);
    }

    public CardRequestBuilder with(String field, Object value) {
        body.put(field, value);
        return this;
    }

    public CardRequestBuilder without(String field) {
        body.remove(field);
        return this;
    }

    public Map<String, Object> build() {
        return new LinkedHashMap<>(body);
    }
}
