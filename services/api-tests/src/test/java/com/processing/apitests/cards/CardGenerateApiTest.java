package com.processing.apitests.cards;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Specs;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static com.processing.apitests.support.LuhnMatcher.passesLuhnCheck;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.oneOf;

/**
 * POST /api/cards/generate.
 *
 * <p>Источники: docs/api-spec.md §2; openapi GenerateCardsRequest (count 1..10000, bins minItems 1);
 * tz/05 §3 (поровну по BIN, баланс 10 000–500 000 ₽, дневной лимит 50 000–300 000 ₽, месячный = дневной × 30);
 * application.properties app.card-generator.* (границы в копейках).</p>
 */
@DisplayName("POST /api/cards/generate")
class CardGenerateApiTest extends ApiTestBase {

    private static ValidatableResponse generate(Object body) {
        return given()
                .spec(Specs.gateway())
                .body(body)
        .when()
                .post(Endpoints.CARDS_GENERATE)
        .then();
    }

    @Test
    @DisplayName("count=10 по 2 BIN → 201, generated = 10, поровну по BIN, параметры карт в диапазонах генератора")
    void generatesCardsEvenlyAcrossBins() {
        generate(Map.of("count", 10, "bins", List.of("400001", "400002")))
                .statusCode(201)
                .body("generated", equalTo(10))
                .body("cards", hasSize(10))
                // GPath: распределение по BIN
                .body("cards.findAll { it.bin == '400001' }", hasSize(5))
                .body("cards.findAll { it.bin == '400002' }", hasSize(5))
                .body("cards.pan", everyItem(passesLuhnCheck()))
                .body("cards.status", everyItem(oneOf("ACTIVE", "INACTIVE", "BLOCKED")))
                // ГЗ-18, ГЗ-19: баланс [1 000 000; 50 000 000), дневной лимит [5 000 000; 30 000 000)
                .body("cards.availableBalance", everyItem(allOf(greaterThanOrEqualTo(1_000_000), lessThan(50_000_000))))
                .body("cards.dailyLimit", everyItem(allOf(greaterThanOrEqualTo(5_000_000), lessThan(30_000_000))))
                // месячный = дневной × 30: проверяем на каждой карте
                .body("cards.findAll { it.monthlyLimit != it.dailyLimit * 30 }", hasSize(0));
    }

    @Test
    @DisplayName("count=1 (нижняя граница ON) → 201, одна карта")
    void generatesSingleCard() {
        generate(Map.of("count", 1, "bins", List.of("400000")))
                .statusCode(201)
                .body("generated", equalTo(1))
                .body("cards", hasSize(1));
    }

    @ParameterizedTest(name = "{0} → 400 «{2}»")
    @CsvSource(delimiter = '|', value = {
        "count = 0 (граница OFF)        | '{\"count\":0,\"bins\":[\"400000\"]}'     | must be greater than or equal to 1",
        "count = 10001 (граница OFF)    | '{\"count\":10001,\"bins\":[\"400000\"]}' | Count exceeds maximum value: 10000",
        "bins = []                      | '{\"count\":1,\"bins\":[]}'               | must not be empty",
        "bin из 3 цифр                  | '{\"count\":1,\"bins\":[\"123\"]}'        | BIN number must contain exactly 6 digits"
    })
    @DisplayName("Невалидный запрос генерации → 400 с причиной")
    void invalidGenerationRequestIsRejected(String equivalenceClass, String body, String message) {
        generate(body)
                .statusCode(400)
                .body("message", equalTo(message))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    @DisplayName("BIN не из справочника → 404 BinNotFoundException")
    void unknownBinIsNotFound() {
        generate(Map.of("count", 1, "bins", List.of("999999")))
                .statusCode(404)
                .body("error", equalTo("BinNotFoundException"));
    }
}
