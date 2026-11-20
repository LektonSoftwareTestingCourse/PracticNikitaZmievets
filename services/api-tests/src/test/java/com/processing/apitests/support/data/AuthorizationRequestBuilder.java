package com.processing.apitests.support.data;

import com.processing.apitests.support.TestIds;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Билдер AuthorizationRequest (docs/api-spec.md §3, модель AuthorizationRequest).
 * Каждый экземпляр получает собственный STAN и merchantId — данные теста не пересекаются с другими.
 */
public final class AuthorizationRequestBuilder {

    private final Map<String, Object> body = new LinkedHashMap<>();

    private AuthorizationRequestBuilder(String pan, Object amount) {
        body.put("mti", "0100");
        body.put("stan", TestIds.stan());
        body.put("pan", pan);
        body.put("processingCode", "000000");
        body.put("amount", amount);
        body.put("currencyCode", "643");
        body.put("transmissionDateTime", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        body.put("terminalId", "TAPI" + TestIds.digits(4));
        body.put("terminalType", "POS");
        body.put("merchantId", TestIds.merchantId());
        body.put("mcc", "5411");
        body.put("acquirerId", "ACQ001");
    }

    /** Покупка (processingCode 000000) по карте {@code pan} на сумму {@code amount} копеек. */
    public static AuthorizationRequestBuilder purchase(String pan, Object amount) {
        return new AuthorizationRequestBuilder(pan, amount);
    }

    public AuthorizationRequestBuilder at(Instant transmissionDateTime) {
        return with("transmissionDateTime", transmissionDateTime.toString());
    }

    /** issuerId обязателен только для прямого вызова Authorization (его обычно ставит Switch). */
    public AuthorizationRequestBuilder issuerId(String issuerId) {
        return with("issuerId", issuerId);
    }

    public AuthorizationRequestBuilder with(String field, Object value) {
        body.put(field, value);
        return this;
    }

    public AuthorizationRequestBuilder without(String field) {
        body.remove(field);
        return this;
    }

    public String stan() {
        return (String) body.get("stan");
    }

    public String merchantId() {
        return (String) body.get("merchantId");
    }

    public Map<String, Object> build() {
        return new LinkedHashMap<>(body);
    }
}
