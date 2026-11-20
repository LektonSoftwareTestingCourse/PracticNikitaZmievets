package com.processing.apitests.cards;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestIds;
import com.processing.apitests.support.data.AuthorizationRequestBuilder;
import com.processing.apitests.support.steps.CardSteps;
import com.processing.apitests.support.steps.TransactionSteps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.oneOf;

/**
 * GET /api/cards/{pan} и GET /api/cards через Gateway (маршрут cards с фильтром ResponseCaching).
 *
 * <p>Источники: docs/api-spec.md §2 (200/404, pan — 16 цифр); openapi /api/cards GET (limit ≥ 1, по умолчанию 50,
 * offset ≥ 0, фильтры status, bin); tz/02 «бонус: кэширование ответов Card Management»;
 * ResponseCachingGatewayFilterFactory (заголовок X-Cache: MISS/HIT, сброс кэша любым успешным не-GET).</p>
 */
@DisplayName("GET /api/cards: чтение, список, кэш Gateway")
class CardQueryApiTest extends ApiTestBase {

    /** BIN 400003 используется для изолированного набора: фильтр по BIN + startDate этого прогона. */
    private static final String LIST_BIN = "400003";
    private static final int LIST_SIZE = 12;
    private static Instant listCreatedFrom;

    @BeforeAll
    static void generateIsolatedCards() {
        listCreatedFrom = Instant.now().minus(1, ChronoUnit.SECONDS);
        given()
                .spec(Specs.gateway())
                .body(Map.of("count", LIST_SIZE, "bins", new String[]{LIST_BIN}))
        .when()
                .post(Endpoints.CARDS_GENERATE)
        .then()
                .statusCode(201);
    }

    @Test
    @DisplayName("GET /api/cards/{pan} → 200, карта по схеме; повторный GET отдаётся из кэша: X-Cache MISS → HIT")
    void cardIsReturnedAndCached() {
        // given: создание карты (успешный POST) сбрасывает кэш Gateway
        String pan = CardSteps.activeCard(1_000_000);

        // when / then: первый запрос — промах кэша
        given()
                .spec(Specs.gateway())
        .when()
                .get(Endpoints.CARD, pan)
        .then()
                .statusCode(200)
                .header("X-Cache", equalTo("MISS"))
                .contentType("application/json")
                .body(matchesJsonSchemaInClasspath("schemas/card.json"))
                .body("pan", equalTo(pan))
                .body("availableBalance", equalTo(1_000_000));

        // второй — попадание
        given()
                .spec(Specs.gateway())
        .when()
                .get(Endpoints.CARD, pan)
        .then()
                .statusCode(200)
                .header("X-Cache", equalTo("HIT"))
                .body("pan", equalTo(pan));
    }

    @Test
    @DisplayName("PATCH (успешный не-GET) сбрасывает кэш: следующий GET — MISS и с новыми данными")
    void successfulPatchInvalidatesCache() {
        String pan = CardSteps.activeCard(1_000_000);
        given().spec(Specs.gateway()).get(Endpoints.CARD, pan);
        given().spec(Specs.gateway()).get(Endpoints.CARD, pan).then().header("X-Cache", "HIT");

        CardSteps.setStatus(pan, "BLOCKED");

        given()
                .spec(Specs.gateway())
        .when()
                .get(Endpoints.CARD, pan)
        .then()
                .statusCode(200)
                .header("X-Cache", equalTo("MISS"))
                .body("status", equalTo("BLOCKED"));
    }

    @Test
    @DisplayName("Карта не найдена → 404 CardNotFoundException от card-management, PAN в сообщении замаскирован")
    void unknownCardIsNotFound() {
        String pan = TestIds.unknownPan("400000");

        given()
                .spec(Specs.gateway())
        .when()
                .get(Endpoints.CARD, pan)
        .then()
                .statusCode(404)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("CardNotFoundException"))
                .body("message", equalTo("Card with PAN 4000********" + pan.substring(12) + " was not found"))
                .body("serviceName", equalTo("card-management"));
    }

    @ParameterizedTest(name = "pan = ''{0}'' → 400")
    @CsvSource({"400000123456789", "40000012345678901", "4000ABC"})
    @DisplayName("pan в пути не из 16 цифр → 400 (КЭ-53, границы 15/17)")
    void malformedPanIsRejected(String pan) {
        given()
                .spec(Specs.gateway())
        .when()
                .get(Endpoints.CARD, pan)
        .then()
                .statusCode(400)
                .body("error", equalTo("ConstraintViolationException"))
                .body("message", equalTo("getCard.pan: PAN number must contain exactly 16 digits"));
    }

    // ---------- Список ----------

    @Test
    @DisplayName("Фильтр bin + startDate: только карты набора, total = 12; GPath: у всех bin = 400003")
    void listFiltersByBinAndCreationDate() {
        given()
                .spec(Specs.gateway())
                .queryParam("bin", LIST_BIN)
                .queryParam("startDate", listCreatedFrom.toString())
                .queryParam("limit", 50)
        .when()
                .get(Endpoints.CARDS)
        .then()
                .statusCode(200)
                .body("total", equalTo(LIST_SIZE))
                .body("cards", hasSize(LIST_SIZE))
                .body("cards.findAll { it.bin == '" + LIST_BIN + "' }", hasSize(LIST_SIZE))
                .body("cards.issuerId", everyItem(equalTo("ISS004")));
    }

    @ParameterizedTest(name = "limit={0}, offset={1} → {2} карт")
    @CsvSource({"1, 0, 1", "5, 0, 5", "5, 10, 2", "5, 12, 0"})
    @DisplayName("Пагинация списка: размер страницы и хвост; total не зависит от страницы")
    void listPagination(int limit, int offset, int expected) {
        given()
                .spec(Specs.gateway())
                .queryParam("bin", LIST_BIN)
                .queryParam("startDate", listCreatedFrom.toString())
                .queryParam("limit", limit)
                .queryParam("offset", offset)
        .when()
                .get(Endpoints.CARDS)
        .then()
                .statusCode(200)
                .body("total", equalTo(LIST_SIZE))
                .body("cards", hasSize(expected));
    }

    @Test
    @DisplayName("Фильтр status: только карты в этом статусе (генератор даёт ACTIVE/INACTIVE/BLOCKED)")
    void listFiltersByStatus() {
        given()
                .spec(Specs.gateway())
                .queryParam("bin", LIST_BIN)
                .queryParam("startDate", listCreatedFrom.toString())
                .queryParam("status", "ACTIVE")
        .when()
                .get(Endpoints.CARDS)
        .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("cards.status", everyItem(equalTo("ACTIVE")));
    }

    @Test
    @DisplayName("Пустая выборка: startDate в будущем → total 0, cards []")
    void emptyCardSelection() {
        given()
                .spec(Specs.gateway())
                .queryParam("startDate", "2099-01-01T00:00:00Z")
        .when()
                .get(Endpoints.CARDS)
        .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("cards", hasSize(0));
    }

    @ParameterizedTest(name = "{0}={1} → {2}")
    @CsvSource({
        "limit,  0,      400",
        "offset, -1,     400",
        "bin,    40000,  400",
        "limit,  10001,  413"
    })
    @DisplayName("Границы параметров списка: limit ≥ 1, offset ≥ 0, bin 6 цифр; limit > 10000 → 413 [DISC-18]")
    void listParameterViolations(String parameter, String value, int status) {
        given()
                .spec(Specs.gateway())
                .queryParam(parameter, value)
        .when()
                .get(Endpoints.CARDS)
        .then()
                .statusCode(status)
                .body("serviceName", equalTo("card-management"))
                .body("error", oneOf("ConstraintViolationException", "TooLargeLimitException"));
    }

    // ---------- Расхождения ----------

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-05] После транзакции GET через Gateway показывает актуальный баланс; факт: устаревший X-Cache HIT")
    void cachedCardReflectsReservation() {
        // given: карта прочитана через Gateway дважды — ответ в кэше
        String pan = CardSteps.activeCard(1_000_000);
        given().spec(Specs.gateway()).get(Endpoints.CARD, pan);
        given().spec(Specs.gateway()).get(Endpoints.CARD, pan);

        // when: резерв делает Authorization напрямую в Card Management (в обход Gateway — кэш не сбрасывается)
        TransactionSteps.authorize(AuthorizationRequestBuilder.purchase(pan, 100_000));

        // then
        given()
                .spec(Specs.gateway())
        .when()
                .get(Endpoints.CARD, pan)
        .then()
                .body("availableBalance", equalTo(900_000));
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-06] limit по умолчанию = 50 (openapi, application.properties); факт: 10")
    void defaultPageSizeIsFifty() {
        given()
                .spec(Specs.gateway())
                .queryParam("bin", LIST_BIN)
                .queryParam("startDate", listCreatedFrom.toString())
        .when()
                .get(Endpoints.CARDS)
        .then()
                .body("cards", hasSize(LIST_SIZE));
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-19] status=FOO в фильтре → 400; факт: 500 MethodArgumentTypeMismatchException")
    void unknownStatusFilterIsBadRequest() {
        given()
                .spec(Specs.gateway())
                .queryParam("status", "FOO")
        .when()
                .get(Endpoints.CARDS)
        .then()
                .statusCode(400);
    }
}
