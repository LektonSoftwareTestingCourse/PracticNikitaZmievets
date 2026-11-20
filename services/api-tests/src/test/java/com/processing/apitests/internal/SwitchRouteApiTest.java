package com.processing.apitests.internal;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Decline;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestIds;
import com.processing.apitests.support.Waits;
import com.processing.apitests.support.data.AuthorizationRequestBuilder;
import com.processing.apitests.support.data.CardRequestBuilder;
import com.processing.apitests.support.steps.CardSteps;
import com.processing.apitests.support.steps.TransactionSteps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;

/**
 * Switch: POST /api/internal/route — маршрутизация по BIN, нормализация, отказы самого Switch.
 *
 * <p>Источники: openapi /api/internal/route; services/switch/.../application.yml (bin-routing 400000..400004 →
 * ISS001..ISS005); RouteService, AuthorizationRequestNormalizer; AuthorizationResponse.unknownBin / authUnavailable.</p>
 */
@DisplayName("Switch: /api/internal/route")
class SwitchRouteApiTest extends ApiTestBase {

    @ParameterizedTest(name = "BIN {0} → issuerId {1} в записи журнала")
    @CsvSource({"400000, ISS001", "400002, ISS003", "400004, ISS005"})
    @DisplayName("Маршрутизация по BIN: issuerId проставляется Switch и попадает в журнал")
    void routesByBinToIssuer(String bin, String issuerId) {
        // given
        String pan = CardSteps.create(CardRequestBuilder.aCard().bin(bin).balance(1_000_000)).getString("pan");

        // when
        given()
                .spec(Specs.switchService())
                .body(AuthorizationRequestBuilder.purchase(pan, 1_000).build())
        .when()
                .post(Endpoints.INTERNAL_ROUTE)
        .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"));

        // then
        Waits.untilDelivered().untilAsserted(() ->
                TransactionSteps.searchByPan(pan).body("transactions[0].issuerId", equalTo(issuerId)));
    }

    @Test
    @DisplayName("Нормализация: terminalId из 5 символов дополняется нулями до 8, merchantId 16 символов 'MERCH0…' → 15")
    void normalizesTerminalAndMerchantIds() {
        String pan = CardSteps.activeCard(1_000_000);

        given()
                .spec(Specs.switchService())
                .body(AuthorizationRequestBuilder.purchase(pan, 1_000)
                        .with("terminalId", "T1234")
                        .with("merchantId", "MERCH0" + "1234567890").build())
        .when()
                .post(Endpoints.INTERNAL_ROUTE)
        .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"));

        Waits.untilDelivered().untilAsserted(() ->
                TransactionSteps.searchByPan(pan)
                        .body("transactions[0].terminalId", equalTo("T1234000"))
                        .body("transactions[0].merchantId", equalTo("MERCH1234567890")));
    }

    @Test
    @DisplayName("Неизвестный BIN → 200, 14/CARD_NOT_FOUND от Switch (mti 0110, без обращения к Authorization)")
    void unknownBinIsDeclinedBySwitchItself() {
        given()
                .spec(Specs.switchService())
                .body(AuthorizationRequestBuilder.purchase(TestIds.unknownPan("999999"), 1_000).build())
        .when()
                .post(Endpoints.INTERNAL_ROUTE)
        .then()
                .statusCode(200)
                .body("mti", equalTo("0110"))
                .body("responseCode", equalTo(Decline.CARD_NOT_FOUND.code()))
                .body("declineReason", equalTo(Decline.CARD_NOT_FOUND.reason()))
                .body("processingTimeMs", equalTo(0));
    }

    @Test
    @DisplayName("Authorization отверг запрос (stan из 5 цифр, 400) → Switch после 3 попыток: 05 «Authorization service unavailable»")
    void authorizationRejectionBecomesServiceUnavailableDecline() {
        // Отказ принадлежит Switch: AuthorizationClient ретраит любой не-APPROVED/DECLINED ответ (backoff 1 с, 2 с),
        // затем RouteService возвращает AuthorizationResponse.authUnavailable. Один вызов на прогон — чтобы не
        // открыть circuit breaker Switch (minimum-number-of-calls 5, порог 50%).
        String pan = CardSteps.activeCard(1_000_000);

        given()
                .spec(Specs.switchService())
                .body(AuthorizationRequestBuilder.purchase(pan, 1_000).with("stan", "12345").build())
        .when()
                .post(Endpoints.INTERNAL_ROUTE)
        .then()
                .statusCode(200)
                .time(lessThan(10_000L))
                .body("mti", equalTo("0110"))
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(Decline.AUTHORIZATION_UNAVAILABLE.code()))
                .body("declineReason", equalTo(Decline.AUTHORIZATION_UNAVAILABLE.reason()))
                .body("rrn", nullValue());
    }
}
