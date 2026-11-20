package com.processing.apitests.cards;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestIds;
import com.processing.apitests.support.steps.CardSteps;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Card Management: POST /api/cards/{pan}/reserve и /rollback — внутренние эндпоинты цепочки авторизации
 * (их вызывает Authorization; docs/architecture.md, шаг «reserve»).
 *
 * <p>Источники: openapi ReserveRequest (amount ≥ 1, rrn) и RollbackRequest; tz/05 §5; test-design КЭ-66…КЭ-71,
 * ГЗ-17, К-10 (повтор reserve с тем же rrn не списывает повторно). Вызываются напрямую в Card Management —
 * так, как их вызывает Authorization.</p>
 */
@DisplayName("Card Management: reserve / rollback")
class CardReservationApiTest extends ApiTestBase {

    private static ValidatableResponse reserve(String pan, Object amount, String rrn) {
        return given()
                .spec(Specs.cardManagement())
                .body(Map.of("amount", amount, "rrn", rrn))
        .when()
                .post(Endpoints.CARD_RESERVE, pan)
        .then();
    }

    private static ValidatableResponse rollback(String pan, Object amount, String rrn) {
        return given()
                .spec(Specs.cardManagement())
                .body(Map.of("amount", amount, "rrn", rrn, "pan", pan))
        .when()
                .post(Endpoints.CARD_ROLLBACK, pan)
        .then();
    }

    @ParameterizedTest(name = "баланс 1 000 000, резерв {0} → 200, остаток {1}")
    @CsvSource({"1, 999999", "150000, 850000", "1000000, 0"})
    @DisplayName("Резерв в пределах баланса (ГЗ-17: 1 и баланс — границы ON)")
    void reserveWithinBalance(long amount, long remaining) {
        String pan = CardSteps.activeCard(1_000_000);

        reserve(pan, amount, TestIds.rrn())
                .statusCode(200)
                .body("pan", equalTo(pan))
                .body("availableBalance", equalTo((int) remaining));
    }

    @Test
    @DisplayName("Резерв = баланс + 1 → 402 InsufficientFundsException, баланс не изменён")
    void reserveAboveBalanceIsPaymentRequired() {
        String pan = CardSteps.activeCard(1_000_000);

        reserve(pan, 1_000_001, TestIds.rrn())
                .statusCode(402)
                .body("error", equalTo("InsufficientFundsException"))
                .body("message", equalTo("Not enough funds on this account"));
        assertEquals(1_000_000, CardSteps.balance(pan));
    }

    @ParameterizedTest(name = "amount={0}, rrn={1} → 400 «{2}»")
    @CsvSource({
        "0,  123456789012, must be greater than 0",
        "-1, 123456789012, must be greater than 0",
        "10, 12345,        RRN number must contain exactly 12 digits"
    })
    @DisplayName("Невалидный ReserveRequest → 400 (amount ≥ 1, rrn — 12 цифр)")
    void invalidReserveRequestIsRejected(long amount, String rrn, String message) {
        String pan = CardSteps.activeCard(1_000_000);

        reserve(pan, amount, rrn)
                .statusCode(400)
                .body("message", equalTo(message));
    }

    @Test
    @DisplayName("Повторная доставка reserve с тем же rrn → 409, средства списаны один раз (К-10)")
    void repeatedReserveWithSameRrnChargesOnce() {
        // given
        String pan = CardSteps.activeCard(1_000_000);
        String rrn = TestIds.rrn();
        reserve(pan, 100_000, rrn).statusCode(200);

        // when
        ValidatableResponse repeated = reserve(pan, 100_000, rrn);

        // then
        repeated
                .statusCode(409)
                .body("error", equalTo("ReservationAlreadyExistsException"));
        assertEquals(900_000, CardSteps.balance(pan));
    }

    @Test
    @DisplayName("Резерв на заблокированной карте → 409 «card status is \"BLOCKED\"»")
    void reserveOnBlockedCardIsConflict() {
        String pan = CardSteps.activeCard(1_000_000);
        CardSteps.setStatus(pan, "BLOCKED");

        reserve(pan, 1, TestIds.rrn())
                .statusCode(409)
                .body("message", equalTo("Can't perform operation: card status is \"BLOCKED\""));
    }

    @Test
    @DisplayName("Резерв на несуществующей карте → 404")
    void reserveOnUnknownCardIsNotFound() {
        reserve(TestIds.unknownPan("400000"), 1, TestIds.rrn())
                .statusCode(404)
                .body("error", equalTo("CardNotFoundException"));
    }

    // ---------- rollback ----------

    @Test
    @DisplayName("rollback резерва → 200, баланс восстановлен; повторный rollback → 409, баланс не растёт")
    void rollbackRestoresBalanceOnce() {
        String pan = CardSteps.activeCard(1_000_000);
        String rrn = TestIds.rrn();
        reserve(pan, 100_000, rrn).statusCode(200);

        rollback(pan, 100_000, rrn)
                .statusCode(200)
                .body("availableBalance", equalTo(1_000_000));
        rollback(pan, 100_000, rrn)
                .statusCode(409)
                .body("error", equalTo("RollbackAlreadySatisfiedException"));
        assertEquals(1_000_000, CardSteps.balance(pan));
    }

    @Test
    @DisplayName("rollback неизвестного rrn → 404 ReservationNotFoundException")
    void rollbackOfUnknownReservationIsNotFound() {
        String pan = CardSteps.activeCard(1_000_000);

        rollback(pan, 100, TestIds.rrn())
                .statusCode(404)
                .body("error", equalTo("ReservationNotFoundException"));
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-13] rollback на сумму больше резерва → отказ; факт: 200, баланс превышает исходный")
    void rollbackCannotExceedReservedAmount() {
        String pan = CardSteps.activeCard(1_000);
        String rrn = TestIds.rrn();
        reserve(pan, 100, rrn).statusCode(200);

        rollback(pan, 500, rrn).statusCode(400);
        assertEquals(1_000, CardSteps.balance(pan), "откат не может вернуть больше, чем было зарезервировано");
    }

    @ParameterizedTest(name = "POST {0} через Gateway → 404")
    @CsvSource({"/api/cards/{pan}/reserve", "/api/cards/{pan}/rollback"})
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-29] reserve/rollback — внутренние вызовы Authorization, публично недоступны; факт: открыты через /api/cards/**")
    void reservationEndpointsAreNotPublic(String path) {
        // tz/02 §3: Gateway проксирует /api/cards/** «как есть», а reserve/rollback — внутренние операции цепочки
        // авторизации (architecture.md). В сочетании с DISC-13 любой клиент Gateway может увеличить баланс карты.
        // given: реальный резерв (напрямую в CMS, как это делает Authorization) — чтобы rollback через Gateway,
        // если маршрут открыт, выполнился успешно, а не получил 404 «резерв не найден» от самого CMS
        String pan = CardSteps.activeCard(1_000);
        String rrn = TestIds.rrn();
        reserve(pan, 10, rrn).statusCode(200);

        // when
        given()
                .spec(Specs.gateway())
                .body(Map.of("amount", 10, "rrn", path.endsWith("reserve") ? TestIds.rrn() : rrn, "pan", pan))
        .when()
                .post(path, pan)
        // then: отказ должен принадлежать Gateway (нет маршрута), а не Card Management
        .then()
                .statusCode(404)
                .body("serviceName", not(equalTo("card-management")));
        assertEquals(990, CardSteps.balance(pan), "баланс не должен меняться публичным вызовом");
    }
}
