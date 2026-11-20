package com.processing.apitests.support;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Генерация уникальных идентификаторов на каждый тест: тесты не делят между собой
 * STAN, RRN, PAN, merchantId и id транзакций, поэтому не зависят от порядка запуска
 * и от данных предыдущих прогонов.
 */
public final class TestIds {

    private TestIds() {
    }

    /** STAN — ровно 6 цифр (api-spec: AuthorizationRequest.stan String(6)). */
    public static String stan() {
        return digits(6);
    }

    /** RRN — ровно 12 цифр (common: @Rrn "^\d{12}$"). */
    public static String rrn() {
        return digits(12);
    }

    /**
     * PAN, которого заведомо нет в Card Management: BIN из справочника + 10 случайных цифр.
     * Вероятность совпасть с реальной картой пренебрежимо мала (10^10 вариантов на BIN).
     */
    public static String unknownPan(String bin) {
        return bin + digits(10);
    }

    /** Произвольный 16-значный PAN для записей журнала, созданных напрямую через Logger. */
    public static String journalPan() {
        return "9" + digits(15);
    }

    /** merchantId — ровно 15 символов (api-spec: String(15)), уникален для теста. */
    public static String merchantId() {
        return "MAPI" + digits(11);
    }

    public static String requestId() {
        return "api-test-" + UUID.randomUUID();
    }

    public static String digits(int length) {
        StringBuilder builder = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            builder.append(random.nextInt(10));
        }
        return builder.toString();
    }
}
