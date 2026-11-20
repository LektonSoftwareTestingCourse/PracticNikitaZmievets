package com.processing.apitests.support;

/**
 * Форматы генерируемых значений — часть контракта, проверяются через matchesPattern,
 * а не через точное значение.
 */
public final class Formats {

    /** RRN — 12 цифр: api-spec (AuthorizationResponse.rrn String(12)), common @Rrn "^\d{12}$". */
    public static final String RRN = "^\\d{12}$";

    /** authCode — 6 символов [A-Z0-9]: api-spec String(6), AuthServiceImpl.generateAuthCode(). */
    public static final String AUTH_CODE = "^[A-Z0-9]{6}$";

    /** PAN — 16 цифр: api-spec Card.pan String(16). */
    public static final String PAN = "^\\d{16}$";

    /** UUID — api-spec Card.id / Transaction.id. */
    public static final String UUID = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    /** expiryDate по контракту — MMYY (api-spec: Card.expiryDate String(4) «MMYY»). */
    public static final String EXPIRY_MMYY = "^(0[1-9]|1[0-2])\\d{2}$";

    /** ISO-8601 timestamp с суффиксом Z. */
    public static final String ISO_INSTANT = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z$";

    private Formats() {
    }
}
