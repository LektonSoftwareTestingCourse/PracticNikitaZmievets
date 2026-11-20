package com.processing.apitests.internal;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Decline;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Formats;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestIds;
import com.processing.apitests.support.data.AuthorizationRequestBuilder;
import com.processing.apitests.support.steps.CardSteps;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Authorization Service напрямую: POST /api/internal/authorize и /api/internal/rollback.
 *
 * <p>Источники: openapi /api/internal/authorize (только 200); фактический маппинг статусов —
 * AuthControllerImpl (@ApiResponses: 200/400/403/404/422/503). Расхождение «openapi: всегда 200 с решением в теле»
 * vs «код: HTTP-статус по причине отказа» — DISC-17. Switch игнорирует HTTP-статус и читает тело,
 * поэтому через Gateway клиент всегда получает 200.</p>
 */
@DisplayName("Authorization: /api/internal/authorize и /api/internal/rollback")
class AuthorizationInternalApiTest extends ApiTestBase {

    private static ValidatableResponse authorize(AuthorizationRequestBuilder request) {
        return given()
                .spec(Specs.authorization())
                .body(request.build())
        .when()
                .post(Endpoints.INTERNAL_AUTHORIZE)
        .then();
    }

    private static ValidatableResponse rollback(Object body) {
        return given()
                .spec(Specs.authorization())
                .body(body)
        .when()
                .post(Endpoints.INTERNAL_ROLLBACK)
        .then();
    }

    private static AuthorizationRequestBuilder purchase(String pan, long amount) {
        return AuthorizationRequestBuilder.purchase(pan, amount).issuerId("ISS001");
    }

    @Test
    @DisplayName("Одобрение → HTTP 200, APPROVED/00, rrn и authCode по формату")
    void approvedIsHttp200() {
        String pan = CardSteps.activeCard(1_000_000);

        authorize(purchase(pan, 10_000))
                .statusCode(200)
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern(Formats.RRN))
                .body("authCode", matchesPattern(Formats.AUTH_CODE));
    }

    @ParameterizedTest(name = "{0} → HTTP {1}, {2}/{3}")
    @CsvSource({
        "BLOCKED,  403, 05, CARD_BLOCKED",
        "INACTIVE, 403, 05, CARD_INACTIVE",
        "EXPIRED,  403, 54, CARD_EXPIRED"
    })
    @DisplayName("Статус карты не ACTIVE → HTTP 403 (AuthControllerImpl: «Card blocked, inactive or expired»)")
    void nonActiveCardIsHttp403(String status, int httpStatus, String code, String reason) {
        String pan = CardSteps.activeCard(1_000_000);
        CardSteps.setStatus(pan, status);

        authorize(purchase(pan, 10_000))
                .statusCode(httpStatus)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code))
                .body("declineReason", equalTo(reason));
    }

    @Test
    @DisplayName("Карта не найдена → HTTP 404, 14/CARD_NOT_FOUND")
    void unknownCardIsHttp404() {
        authorize(purchase(TestIds.unknownPan("400000"), 10_000))
                .statusCode(404)
                .body("responseCode", equalTo(Decline.CARD_NOT_FOUND.code()))
                .body("declineReason", equalTo(Decline.CARD_NOT_FOUND.reason()));
    }

    @Test
    @DisplayName("Недостаточно средств → HTTP 422, 51/INSUFFICIENT_FUNDS")
    void insufficientFundsIsHttp422() {
        String pan = CardSteps.activeCard(1_000);

        authorize(purchase(pan, 1_001))
                .statusCode(422)
                .body("responseCode", equalTo(Decline.INSUFFICIENT_FUNDS.code()))
                .body("declineReason", equalTo(Decline.INSUFFICIENT_FUNDS.reason()));
    }

    @Test
    @DisplayName("Превышение лимита → HTTP 400 (ветка default в AuthControllerImpl), 61/EXCEEDS_AMOUNT_LIMIT")
    void exceededLimitIsHttp400() {
        String pan = CardSteps.activeCard(1_000_000, 1_000, 10_000);

        authorize(purchase(pan, 1_001))
                .statusCode(400)
                .body("responseCode", equalTo(Decline.EXCEEDS_AMOUNT_LIMIT.code()))
                .body("declineReason", equalTo(Decline.EXCEEDS_AMOUNT_LIMIT.reason()));
    }

    @ParameterizedTest(name = "{0} = ''{1}'' → 400")
    @CsvSource({
        "stan,           12345",
        "processingCode, 00000",
        "issuerId,       ''"
    })
    @DisplayName("Нарушение @Valid AuthorizationRequest → 400, тело — стандартная ошибка Spring (без ErrorResponse)")
    void invalidRequestIsRejectedBySpringValidation(String field, String value) {
        authorize(purchase(TestIds.unknownPan("400000"), 1).with(field, value))
                .statusCode(400)
                .body("status", equalTo(400))
                .body("error", equalTo("Bad Request"))
                .body("path", equalTo(Endpoints.INTERNAL_AUTHORIZE));
    }

    // ---------- rollback ----------

    @Test
    @DisplayName("rollback одобренной транзакции → 200 APPROVED/00, баланс восстановлен")
    void rollbackRestoresBalance() {
        // given
        String pan = CardSteps.activeCard(1_000_000);
        String rrn = authorize(purchase(pan, 250_000)).statusCode(200).extract().path("rrn");
        assertEquals(750_000, CardSteps.balance(pan));

        // when
        rollback(Map.of("rrn", rrn, "pan", pan, "amount", 250_000))
                // then
                .statusCode(200)
                .body("rrn", equalTo(rrn))
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("declineReason", nullValue());
        assertEquals(1_000_000, CardSteps.balance(pan));
    }

    @Test
    @DisplayName("Повторный rollback (повторная доставка reversal) → 409 ALREADY_ROLLED_BACK, баланс не растёт")
    void repeatedRollbackIsConflict() {
        String pan = CardSteps.activeCard(1_000_000);
        String rrn = authorize(purchase(pan, 250_000)).statusCode(200).extract().path("rrn");
        Map<String, Object> reversal = Map.of("rrn", rrn, "pan", pan, "amount", 250_000);
        rollback(reversal).statusCode(200);

        rollback(reversal)
                .statusCode(409)
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("ALREADY_ROLLED_BACK"));
        assertEquals(1_000_000, CardSteps.balance(pan));
    }

    @Test
    @DisplayName("rollback неизвестного rrn → 404 TRANSACTION_NOT_FOUND (код 14)")
    void rollbackOfUnknownRrnIsNotFound() {
        String pan = CardSteps.activeCard(1_000_000);

        rollback(Map.of("rrn", TestIds.rrn(), "pan", pan, "amount", 100))
                .statusCode(404)
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("TRANSACTION_NOT_FOUND"));
    }

    @ParameterizedTest(name = "{0} → 400")
    @CsvSource({
        "'{\"rrn\":\"123\",\"pan\":\"4000000000000000\",\"amount\":1}',              rrn не из 12 цифр",
        "'{\"rrn\":\"123456789012\",\"pan\":\"4000\",\"amount\":1}',                pan не из 16 цифр",
        "'{\"rrn\":\"123456789012\",\"pan\":\"4000000000000000\",\"amount\":0}',     amount = 0"
    })
    @DisplayName("Невалидный RollbackRequest → 400")
    void invalidRollbackRequestIsRejected(String body, String equivalenceClass) {
        rollback(body)
                .statusCode(400)
                .body("path", equalTo(Endpoints.INTERNAL_ROLLBACK));
    }
}
