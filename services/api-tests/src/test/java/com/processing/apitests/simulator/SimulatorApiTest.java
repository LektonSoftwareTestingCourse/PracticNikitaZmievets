package com.processing.apitests.simulator;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.ServiceUrls;
import com.processing.apitests.support.Specs;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.oneOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Симуляторы через Gateway: /api/simulator/terminal/** и /api/simulator/merchant/**.
 * Источники: docs/api-spec.md §4; openapi TerminalSimulatorRequest / MerchantSimulatorRequest / SimulatorResponse;
 * TerminalRunRequest (tps обязателен — DISC-22), SimulatorRequest (tps по умолчанию 100).
 *
 * <p>Симуляторы работают на картах из Card Management и сами создают транзакции, поэтому проверяются
 * инварианты ответа (сумма approved + declined, размер массива), а не конкретные решения.</p>
 */
@DisplayName("Симуляторы терминалов и мерчантов")
class SimulatorApiTest extends ApiTestBase {

    private static JsonPath run(String path, Map<String, Object> body) {
        return given()
                .spec(Specs.gateway())
                .body(body)
        .when()
                .post(path)
        .then()
                .statusCode(200)
                .body("totalSubmitted", equalTo(body.get("count")))
                .body("transactions", hasSize((Integer) body.get("count")))
                .body("transactions.status", everyItem(oneOf("APPROVED", "DECLINED")))
                .body("elapsedMs", greaterThanOrEqualTo(0))
                .extract().jsonPath();
    }

    @ParameterizedTest(name = "terminal scenario={0}")
    @ValueSource(strings = {"normal", "mixed", "high_value", "night_time", "declines_test"})
    @DisplayName("terminal/run: каждый сценарий из enum → 200, approved + declined = totalSubmitted")
    void terminalRunScenario(String scenario) {
        JsonPath result = run(Endpoints.TERMINAL_RUN, Map.of("count", 3, "scenario", scenario, "tps", 20));

        assertEquals(result.getInt("totalSubmitted"), result.getInt("approved") + result.getInt("declined"));
    }

    @ParameterizedTest(name = "merchant scenario={0}")
    @ValueSource(strings = {"grocery", "electronics", "restaurant", "travel"})
    @DisplayName("merchant/run: каждый сценарий из enum → 200, approved + declined = totalSubmitted")
    void merchantRunScenario(String scenario) {
        JsonPath result = run(Endpoints.MERCHANT_RUN, Map.of("count", 2, "scenario", scenario));

        assertEquals(result.getInt("totalSubmitted"), result.getInt("approved") + result.getInt("declined"));
    }

    @Test
    @DisplayName("merchant/run с явными mccCodes → 200 (mccCodes имеет приоритет над сценарием)")
    void merchantRunWithExplicitMcc() {
        run(Endpoints.MERCHANT_RUN, Map.of("count", 2, "scenario", "grocery", "mccCodes", new String[]{"5812"}));
    }

    @ParameterizedTest(name = "{0} → 400")
    @CsvSource(delimiter = '|', value = {
        "count = 0           | '{\"count\":0,\"scenario\":\"normal\",\"tps\":10}'  | count",
        "scenario вне enum   | '{\"count\":1,\"scenario\":\"foo\",\"tps\":10}'     | foo",
        "tps = 0             | '{\"count\":1,\"scenario\":\"normal\",\"tps\":0}'   | tps"
    })
    @DisplayName("terminal/run: невалидный запрос → 400 от terminal-simulator")
    void terminalRunValidation(String equivalenceClass, String body, String mentioned) {
        given()
                .spec(Specs.gateway())
                .body(body)
        .when()
                .post(Endpoints.TERMINAL_RUN)
        .then()
                .statusCode(400)
                .body("serviceName", equalTo("terminal-simulator"))
                .body("message", containsString(mentioned));
    }

    @ParameterizedTest(name = "{0} → 400 «{2}»")
    @CsvSource(delimiter = '|', value = {
        "count = 0          | '{\"count\":0,\"scenario\":\"grocery\"}'  | count : must be greater than or equal to 1",
        "без scenario       | '{\"count\":1}'                           | scenario : Scenario is required",
        "tps = 0            | '{\"count\":1,\"scenario\":\"grocery\",\"tps\":0}' | tps : tps must be >= 1"
    })
    @DisplayName("merchant/run: невалидный запрос → 400 с полем и правилом")
    void merchantRunValidation(String equivalenceClass, String body, String message) {
        given()
                .spec(Specs.gateway())
                .body(body)
        .when()
                .post(Endpoints.MERCHANT_RUN)
        .then()
                .statusCode(400)
                .body("serviceName", equalTo("Merchant acquirer simulator"))
                .body("message", equalTo(message));
    }

    @Test
    @DisplayName("merchant/run с несуществующим MCC → 404 «Merchants with given mcc … not found»")
    void merchantRunWithUnknownMccIsNotFound() {
        given()
                .spec(Specs.gateway())
                .body(Map.of("count", 1, "scenario", "grocery", "mccCodes", new String[]{"0000"}))
        .when()
                .post(Endpoints.MERCHANT_RUN)
        .then()
                .statusCode(404)
                .body("message", equalTo("Merchants with given mcc ([0000]) not found"));
    }

    @Test
    @DisplayName("merchant/fee: нет обязательных полей → 400; неизвестная транзакция → 404")
    void merchantFeeErrors() {
        given()
                .spec(Specs.gateway())
                .body("{}")
        .when()
                .post(Endpoints.MERCHANT_FEE)
        .then()
                .statusCode(400)
                .body("message", equalTo("pan : must not be blank; stan : must not be blank; "
                        + "terminalId : must not be blank; transmissionDateTime : must not be null"));

        given()
                .spec(Specs.gateway())
                .body(Map.of("transmissionDateTime", "2001-01-01T00:00:00Z", "pan", "4000000000000000",
                        "stan", "000001", "amount", 1, "terminalId", "TNOTEXST"))
        .when()
                .post(Endpoints.MERCHANT_FEE)
        .then()
                .statusCode(404)
                .body("message", equalTo("Acquirer fee not found for stan = 000001"));
    }

    @Test
    @DisplayName("GET /api/simulator/merchants напрямую в Merchant Simulator → 200, непустой список мерчантов")
    void merchantsListDirect() {
        given()
                .baseUri(ServiceUrls.MERCHANT)
                .accept(ContentType.JSON)
        .when()
                .get(Endpoints.MERCHANTS)
        .then()
                .statusCode(200)
                .body("$", not(empty()))
                .body("mcc", everyItem(matchesPattern("^\\d{4}$")));
    }

    // ---------- Расхождения ----------

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-22] terminal/run с телом из api-spec ({count, scenario}) → 200; факт: 400, tps обязателен")
    void terminalRunAcceptsSpecBody() {
        given()
                .spec(Specs.gateway())
                .body(Map.of("count", 1, "scenario", "normal"))
        .when()
                .post(Endpoints.TERMINAL_RUN)
        .then()
                .statusCode(200);
    }

    // ---------- Непрерывный режим (есть в коде, нет в спецификации — DISC-23) ----------

    @Test
    @DisplayName("start-continuous → 200; повторный старт → 422 «already running»; stop → 200 и идемпотентен")
    void continuousModeLifecycle() {
        // given / when: tps=1 — минимальная нагрузка, режим живёт доли секунды
        try {
            given()
                    .spec(Specs.gateway())
                    .body(Map.of("tps", 1, "transactionType", "NORMAL"))
            .when()
                    .post("/api/simulator/terminal/start-continuous")
            .then()
                    .statusCode(200);

            // then: второй запуск — конфликт состояния (GlobalExceptionHandler: IllegalStateException → 422)
            given()
                    .spec(Specs.gateway())
                    .body(Map.of("tps", 1, "transactionType", "NORMAL"))
            .when()
                    .post("/api/simulator/terminal/start-continuous")
            .then()
                    .statusCode(422)
                    .body("error", equalTo("Illegal state"))
                    .body("message", equalTo("Continuous simulation is already running!"));
        } finally {
            given().spec(Specs.gateway()).when().post("/api/simulator/terminal/stop").then().statusCode(200);
        }
        // повторная остановка не ошибка
        given().spec(Specs.gateway()).when().post("/api/simulator/terminal/stop").then().statusCode(200);
    }

    @ParameterizedTest(name = "{0} → 400")
    @CsvSource(delimiter = '|', value = {
        "tps = 0                  | '{\"tps\":0,\"transactionType\":\"NORMAL\"}'",
        "transactionType вне enum | '{\"tps\":1,\"transactionType\":\"FAST\"}'",
        "без transactionType      | '{\"tps\":1}'"
    })
    @DisplayName("start-continuous: невалидный запрос → 400, режим не запускается")
    void continuousModeValidation(String equivalenceClass, String body) {
        given()
                .spec(Specs.gateway())
                .body(body)
        .when()
                .post("/api/simulator/terminal/start-continuous")
        .then()
                .statusCode(400)
                .body("serviceName", equalTo("terminal-simulator"));
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-23] POST /api/simulator/terminal/start → 202 {runId, status: RUNNING} (openapi); факт: 500")
    void terminalStartIsImplemented() {
        given()
                .spec(Specs.gateway())
                .body(Map.of("count", 1, "scenario", "normal"))
        .when()
                .post(Endpoints.TERMINAL_START)
        .then()
                .statusCode(202);
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-23] GET /api/simulator/terminal/status → 200 (openapi); факт: 500 «No static resource»")
    void terminalStatusIsImplemented() {
        given()
                .spec(Specs.gateway())
        .when()
                .get(Endpoints.TERMINAL_STATUS)
        .then()
                .statusCode(200);
    }
}
