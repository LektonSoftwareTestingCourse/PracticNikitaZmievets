package com.processing.apitests.transactions;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Decline;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestIds;
import com.processing.apitests.support.data.AuthorizationRequestBuilder;
import com.processing.apitests.support.steps.CardSteps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Валидация POST /api/transactions на Gateway.
 *
 * <p>Источник правил: tz/02-gateway.md §2 «Валидация» и модель AuthorizationRequest в docs/api-spec.md;
 * реализация — services/gateway/.../TransactionRequestValidator.java. Формат ошибки — tz/02 §2:
 * {@code {"error":"VALIDATION_ERROR","message":"Field 'pan' must be exactly 16 digits","timestamp":...}}.</p>
 *
 * <p>Каждый тест нарушает ровно одно правило, все остальные поля валидны (базовая карта не нужна:
 * Gateway отсекает запрос до обращения к Switch, поэтому PAN может быть любым 16-значным).
 * Для каждого отказа проверяется: статус 400, код VALIDATION_ERROR, текст с именем поля и правилом,
 * serviceName=gateway — отказ принадлежит Gateway, а не downstream-сервису.</p>
 */
@DisplayName("POST /api/transactions: валидация на Gateway")
class TransactionValidationApiTest extends ApiTestBase {

    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";

    private static AuthorizationRequestBuilder validRequest() {
        return AuthorizationRequestBuilder.purchase(TestIds.unknownPan("400000"), 100);
    }

    private static void expectGatewayValidationError(Map<String, Object> body, String message) {
        given()
                .spec(Specs.gateway())
                .body(body)
        .when()
                .post(Endpoints.TRANSACTIONS)
        .then()
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo(VALIDATION_ERROR))
                .body("message", equalTo(message))
                .body("serviceName", equalTo("gateway"))
                .body("timestamp", notNullValue())
                .body("$", hasKey("retryAfterMs"))
                .body("retryAfterMs", nullValue());
    }

    // ---------- Обязательность ----------

    @ParameterizedTest(name = "поле {0} отсутствует → 400 «Field ''{0}'' is required»")
    @ValueSource(strings = {"mti", "stan", "pan", "processingCode", "amount", "currencyCode",
        "transmissionDateTime", "terminalId", "merchantId", "mcc", "acquirerId"})
    @DisplayName("Отсутствие обязательного поля (api-spec: AuthorizationRequest, «Обязательное = Да»)")
    void missingRequiredFieldIsRejected(String field) {
        // given
        Map<String, Object> body = validRequest().without(field).build();

        // when / then
        expectGatewayValidationError(body, "Field '" + field + "' is required");
    }

    @ParameterizedTest(name = "поле {0} = \"   \" → 400 «Field ''{0}'' is required»")
    @ValueSource(strings = {"mti", "stan", "pan", "processingCode", "currencyCode",
        "terminalId", "merchantId", "mcc", "acquirerId"})
    @DisplayName("Пустая строка (только пробелы) в строковом поле приравнивается к отсутствию")
    void blankStringFieldIsRejected(String field) {
        Map<String, Object> body = validRequest().with(field, "   ").build();

        expectGatewayValidationError(body, "Field '" + field + "' is required");
    }

    @Test
    @DisplayName("terminalType необязателен (api-spec: «Обязательное = Нет») → запрос принимается")
    void terminalTypeIsOptional() {
        // given: карта существует, чтобы решение принял Authorization, а не отсёк Switch
        String pan = CardSteps.activeCard(1_000_000);
        Map<String, Object> body = AuthorizationRequestBuilder.purchase(pan, 100).without("terminalType").build();

        given()
                .spec(Specs.gateway())
                .body(body)
        .when()
                .post(Endpoints.TRANSACTIONS)
        .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"));
    }

    // ---------- mti ----------

    @ParameterizedTest(name = "mti = {0} → 400")
    @ValueSource(strings = {"0200", "0400", "0110", "100", "01000"})
    @DisplayName("mti отличный от \"0100\" (tz/02 §2: mti = \"0100\")")
    void mtiOtherThanAuthRequestIsRejected(String mti) {
        expectGatewayValidationError(validRequest().with("mti", mti).build(), "Field 'mti' must be '0100'");
    }

    // ---------- pan: длина 16 и только цифры ----------

    @ParameterizedTest(name = "pan = {0} ({1}) → 400")
    @CsvSource({
        "400000123456789,   15 цифр — граница снизу",
        "40000012345678901, 17 цифр — граница сверху",
        "40000012345678AB,  16 символов, но не цифры",
        "4000 0012 3456 78, пробелы внутри"
    })
    @DisplayName("pan не из 16 цифр (tz/02 §2: pan — ровно 16 цифр)")
    void panOutsideSixteenDigitsIsRejected(String pan, String equivalenceClass) {
        expectGatewayValidationError(validRequest().with("pan", pan).build(), "Field 'pan' must be exactly 16 digits");
    }

    // ---------- amount > 0 ----------

    @ParameterizedTest(name = "amount = {0} → 400")
    @ValueSource(longs = {0, -1, -150_000})
    @DisplayName("amount ≤ 0 (tz/02 §2: amount > 0; граница 0 и ближайшее значение -1)")
    void nonPositiveAmountIsRejected(long amount) {
        expectGatewayValidationError(validRequest().with("amount", amount).build(), "Field 'amount' must be > 0");
    }

    @Test
    @DisplayName("amount = 1 (минимальное допустимое, граница ON) → проходит валидацию и одобряется")
    void minimalPositiveAmountIsAccepted() {
        String pan = CardSteps.activeCard(1_000_000);

        given()
                .spec(Specs.gateway())
                .body(AuthorizationRequestBuilder.purchase(pan, 1).build())
        .when()
                .post(Endpoints.TRANSACTIONS)
        .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"));
    }

    @Test
    @DisplayName("amount строкой-не-числом → 400 «Request body must be valid JSON»")
    void nonNumericAmountIsRejectedAsInvalidJson() {
        expectGatewayValidationError(validRequest().with("amount", "abc").build(), "Request body must be valid JSON");
    }

    // ---------- currencyCode: 3 символа ----------

    @ParameterizedTest(name = "currencyCode = {0} → 400")
    @ValueSource(strings = {"64", "6430", "1"})
    @DisplayName("currencyCode длиной ≠ 3 (tz/02 §2: 3 символа; границы 2 и 4)")
    void currencyCodeOfWrongLengthIsRejected(String currencyCode) {
        expectGatewayValidationError(validRequest().with("currencyCode", currencyCode).build(),
                "Field 'currencyCode' must be exactly 3 characters");
    }

    // ---------- mcc: 4 цифры ----------

    @ParameterizedTest(name = "mcc = {0} → 400")
    @ValueSource(strings = {"541", "54111", "54A1"})
    @DisplayName("mcc не из 4 цифр (tz/02 §2: mcc — 4 цифры; границы 3 и 5, класс «не цифры»)")
    void mccOutsideFourDigitsIsRejected(String mcc) {
        expectGatewayValidationError(validRequest().with("mcc", mcc).build(), "Field 'mcc' must be exactly 4 digits");
    }

    // ---------- синтаксис тела ----------

    @ParameterizedTest(name = "тело ''{0}'' → 400")
    @ValueSource(strings = {"{bad json", "", "[]"})
    @DisplayName("Синтаксически некорректное тело → 400 «Request body must be valid JSON»")
    void malformedBodyIsRejected(String rawBody) {
        given()
                .spec(Specs.gateway())
                .body(rawBody)
        .when()
                .post(Endpoints.TRANSACTIONS)
        .then()
                .statusCode(400)
                .body("error", equalTo(VALIDATION_ERROR))
                .body("message", equalTo("Request body must be valid JSON"))
                .body("serviceName", equalTo("gateway"));
    }

    @Test
    @DisplayName("transmissionDateTime не в ISO-8601 → 400 «Request body must be valid JSON»")
    void transmissionDateTimeInWrongFormatIsRejected() {
        expectGatewayValidationError(validRequest().with("transmissionDateTime", "2026-13-01").build(),
                "Request body must be valid JSON");
    }

    // ---------- Расхождения: правила из спецификации, которые Gateway не проверяет ----------

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-07] stan из 5 цифр → 400 (api-spec stan String(6), openapi ^\\d{6}$); факт: 200 DECLINED/05 через 3 с")
    void stanOfFiveDigitsMustBeRejectedByGateway() {
        expectGatewayValidationError(validRequest().with("stan", "12345").build(),
                "Field 'stan' must be exactly 6 digits");
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-08] currencyCode=\"RUB\" → 400 (openapi ^\\d{3}$); факт: одобряется")
    void alphabeticCurrencyCodeMustBeRejected() {
        String pan = CardSteps.activeCard(1_000_000);

        given()
                .spec(Specs.gateway())
                .body(AuthorizationRequestBuilder.purchase(pan, 100).with("currencyCode", "RUB").build())
        .when()
                .post(Endpoints.TRANSACTIONS)
        .then()
                .statusCode(400)
                .body("error", equalTo(VALIDATION_ERROR));
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-09] terminalType=\"KIOSK\" → 400 (enum POS/ATM/ECOM); факт: одобряется")
    void terminalTypeOutsideEnumMustBeRejected() {
        String pan = CardSteps.activeCard(1_000_000);

        given()
                .spec(Specs.gateway())
                .body(AuthorizationRequestBuilder.purchase(pan, 100).with("terminalType", "KIOSK").build())
        .when()
                .post(Endpoints.TRANSACTIONS)
        .then()
                .statusCode(400)
                .body("error", equalTo(VALIDATION_ERROR));
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-10] amount=0.5 (дробные копейки) → 400 (openapi amount: integer); факт: одобряется")
    void fractionalAmountMustBeRejected() {
        String pan = CardSteps.activeCard(1_000_000);

        given()
                .spec(Specs.gateway())
                .body(AuthorizationRequestBuilder.purchase(pan, 0.5).build())
        .when()
                .post(Endpoints.TRANSACTIONS)
        .then()
                .statusCode(400)
                .body("error", equalTo(VALIDATION_ERROR));
    }

    @Test
    @DisplayName("Отказ Switch при неизвестном BIN отличим от отказа Gateway: 200 + DECLINED/14, mti=0110")
    void unknownBinPassesGatewayAndIsDeclinedBySwitch() {
        // Граница ответственности: формат PAN верный → Gateway пропускает; BIN 999999 нет в bin-routing Switch.
        given()
                .spec(Specs.gateway())
                .body(AuthorizationRequestBuilder.purchase(TestIds.unknownPan("999999"), 100).build())
        .when()
                .post(Endpoints.TRANSACTIONS)
        .then()
                .statusCode(200)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(Decline.CARD_NOT_FOUND.code()))
                .body("declineReason", equalTo(Decline.CARD_NOT_FOUND.reason()))
                .body("mti", equalTo("0110"))
                .body("processingTimeMs", equalTo(0))
                .body("rrn", nullValue())
                .body("stan", matchesPattern("^\\d{6}$"));
    }
}
