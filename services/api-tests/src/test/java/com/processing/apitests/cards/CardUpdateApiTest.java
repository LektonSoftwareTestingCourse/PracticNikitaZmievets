package com.processing.apitests.cards;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestIds;
import com.processing.apitests.support.data.CardRequestBuilder;
import com.processing.apitests.support.steps.CardSteps;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PATCH / DELETE /api/cards/{pan} и POST /api/cards/bulk-update.
 *
 * <p>Источники: tz/05 §2 (PATCH меняет только переданные поля; DELETE — soft delete), openapi CardUpdateRequest,
 * Card.withData (monthlyLimit ≥ dailyLimit, лимиты ≥ 0), CardServiceImpl.bulkUpdateStatus.</p>
 */
@DisplayName("PATCH/DELETE /api/cards/{pan}, bulk-update")
class CardUpdateApiTest extends ApiTestBase {

    private static ValidatableResponse patch(String pan, Object body) {
        return given()
                .spec(Specs.gateway())
                .body(body)
        .when()
                .patch(Endpoints.CARD, pan)
        .then();
    }

    @ParameterizedTest(name = "status → {0}")
    @ValueSource(strings = {"BLOCKED", "INACTIVE", "EXPIRED", "ACTIVE"})
    @DisplayName("PATCH status меняет только статус (R-CMS-06): лимиты и баланс прежние")
    void patchStatusChangesOnlyStatus(String status) {
        // given
        JsonPath before = CardSteps.create(CardRequestBuilder.aCard().balance(777_000).dailyLimit(1_000).monthlyLimit(2_000));
        String pan = before.getString("pan");

        // when / then
        patch(pan, Map.of("status", status))
                .statusCode(200)
                .body("status", equalTo(status))
                .body("availableBalance", equalTo(777_000))
                .body("dailyLimit", equalTo(1_000))
                .body("monthlyLimit", equalTo(2_000))
                .body("expiryDate", equalTo(before.getString("expiryDate")));
    }

    @Test
    @DisplayName("PATCH лимитов и баланса → 200, новые значения видны при чтении из Card Management")
    void patchLimitsAndBalance() {
        String pan = CardSteps.activeCard(1_000_000);

        patch(pan, Map.of("dailyLimit", 5_000, "monthlyLimit", 5_000, "availableBalance", 42))
                .statusCode(200)
                .body("dailyLimit", equalTo(5_000))
                .body("monthlyLimit", equalTo(5_000));

        JsonPath stored = CardSteps.read(pan);
        assertEquals(42, stored.getInt("availableBalance"));
    }

    @Test
    @DisplayName("PATCH dailyLimit > monthlyLimit → 400 «Daily limit can not be larger than monthly limit»")
    void patchDailyAboveMonthlyIsRejected() {
        String pan = CardSteps.activeCard(1_000_000, 1_000, 2_000);

        patch(pan, Map.of("dailyLimit", 2_001))
                .statusCode(400)
                .body("error", equalTo("IllegalArgumentException"))
                .body("message", equalTo("Daily limit can not be larger than monthly limit"));
    }

    @Test
    @DisplayName("PATCH dailyLimit = monthlyLimit (граница ON) → 200")
    void patchDailyEqualToMonthlyIsAccepted() {
        String pan = CardSteps.activeCard(1_000_000, 1_000, 2_000);

        patch(pan, Map.of("dailyLimit", 2_000)).statusCode(200).body("dailyLimit", equalTo(2_000));
    }

    @ParameterizedTest(name = "{0} = -1 → 400")
    @ValueSource(strings = {"dailyLimit", "monthlyLimit"})
    @DisplayName("PATCH отрицательного лимита → 400 «Value can not be negative» (КЭ-74)")
    void patchNegativeLimitIsRejected(String field) {
        String pan = CardSteps.activeCard(1_000_000);

        patch(pan, Map.of(field, -1))
                .statusCode(400)
                .body("message", equalTo("Value can not be negative"));
    }

    @Test
    @DisplayName("PATCH несуществующей карты → 404")
    void patchUnknownCardIsNotFound() {
        patch(TestIds.unknownPan("400000"), Map.of("status", "BLOCKED"))
                .statusCode(404)
                .body("error", equalTo("CardNotFoundException"));
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-19] PATCH status=DELETED (вне enum CardUpdateRequest) → 400; факт: 500")
    void patchDeletedStatusIsBadRequest() {
        String pan = CardSteps.activeCard(1_000_000);

        patch(pan, Map.of("status", "DELETED")).statusCode(400);
    }

    // ---------- DELETE ----------

    @Test
    @DisplayName("DELETE → 204 без тела; карта больше не выдаётся (404) — soft delete (R-CMS-07)")
    void deletedCardIsNoLongerReturned() {
        String pan = CardSteps.activeCard(1_000_000);

        given()
                .spec(Specs.gateway())
        .when()
                .delete(Endpoints.CARD, pan)
        .then()
                .statusCode(204)
                .body(equalTo(""));

        given()
                .spec(Specs.cardManagement())
        .when()
                .get(Endpoints.CARD, pan)
        .then()
                .statusCode(404)
                .body("error", equalTo("CardNotFoundException"));
    }

    @Test
    @DisplayName("Повторный DELETE и DELETE несуществующей карты → 404")
    void deleteOfMissingCardIsNotFound() {
        String pan = CardSteps.activeCard(1_000_000);
        CardSteps.delete(pan);

        given().spec(Specs.gateway()).when().delete(Endpoints.CARD, pan).then().statusCode(404);
        given().spec(Specs.gateway()).when().delete(Endpoints.CARD, TestIds.unknownPan("400000")).then().statusCode(404);
    }

    // ---------- bulk-update ----------

    @Test
    @DisplayName("bulk-update по списку PAN → 200 {updated: N}, статус применён к каждой карте")
    void bulkUpdateByPans() {
        String first = CardSteps.activeCard(1_000);
        String second = CardSteps.activeCard(1_000);

        given()
                .spec(Specs.gateway())
                .body(Map.of("pans", List.of(first, second), "status", "BLOCKED"))
        .when()
                .post(Endpoints.CARDS_BULK_UPDATE)
        .then()
                .statusCode(200)
                .body("updated", equalTo(2));

        assertEquals("BLOCKED", CardSteps.read(first).getString("status"));
        assertEquals("BLOCKED", CardSteps.read(second).getString("status"));
    }

    @Test
    @DisplayName("bulk-update без bins и pans → 400; с обоими сразу → 400 (взаимоисключающие параметры)")
    void bulkUpdateRequiresExactlyOneSelector() {
        given()
                .spec(Specs.gateway())
                .body(Map.of("status", "ACTIVE"))
        .when()
                .post(Endpoints.CARDS_BULK_UPDATE)
        .then()
                .statusCode(400)
                .body("message", equalTo("Either bin or pans must be provided"));

        given()
                .spec(Specs.gateway())
                .body(Map.of("status", "ACTIVE", "bins", List.of("400000"), "pans", List.of(TestIds.unknownPan("400000"))))
        .when()
                .post(Endpoints.CARDS_BULK_UPDATE)
        .then()
                .statusCode(400)
                .body("message", equalTo("Only one of bin or pans must be provided"));
    }
}
