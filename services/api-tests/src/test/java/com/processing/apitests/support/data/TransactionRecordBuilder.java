package com.processing.apitests.support.data;

import com.processing.apitests.support.TestIds;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Билдер полной записи Transaction для POST /api/internal/log
 * (docs/api-spec.md §7, модель Transaction; common TransactionRequest).
 *
 * <p>Используется, чтобы подготовить журнал через API Logger'а с полностью контролируемыми
 * полями (статус, даты, MCC, issuerId, причина отказа) — это даёт детерминированный оракул
 * для тестов поиска и не требует прямого доступа к БД.</p>
 */
public final class TransactionRecordBuilder {

    private final Map<String, Object> body = new LinkedHashMap<>();

    private TransactionRecordBuilder() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        body.put("id", UUID.randomUUID().toString());
        body.put("mti", "0100");
        body.put("stan", TestIds.stan());
        body.put("rrn", TestIds.rrn());
        body.put("pan", TestIds.journalPan());
        body.put("processingCode", "000000");
        body.put("amount", 150_000);
        body.put("currencyCode", "643");
        body.put("terminalId", "TAPI" + TestIds.digits(4));
        body.put("terminalType", "POS");
        body.put("merchantId", TestIds.merchantId());
        body.put("mcc", "5411");
        body.put("acquirerId", "ACQ001");
        body.put("issuerId", "ISS001");
        body.put("acquiringFee", null);
        body.put("status", "APPROVED");
        body.put("declineReason", null);
        body.put("authCode", "API001");
        body.put("processingTimeMs", 5);
        body.put("transmissionDateTime", now.toString());
        body.put("createdAt", now.toString());
    }

    public static TransactionRecordBuilder approved() {
        return new TransactionRecordBuilder();
    }

    public static TransactionRecordBuilder declined(String reason) {
        return new TransactionRecordBuilder()
                .with("status", "DECLINED")
                .with("declineReason", reason)
                .with("rrn", null)
                .with("authCode", null);
    }

    public TransactionRecordBuilder with(String field, Object value) {
        body.put(field, value);
        return this;
    }

    public TransactionRecordBuilder without(String field) {
        body.remove(field);
        return this;
    }

    public Object get(String field) {
        return body.get(field);
    }

    public String id() {
        return (String) body.get("id");
    }

    public Map<String, Object> build() {
        return new LinkedHashMap<>(body);
    }
}
