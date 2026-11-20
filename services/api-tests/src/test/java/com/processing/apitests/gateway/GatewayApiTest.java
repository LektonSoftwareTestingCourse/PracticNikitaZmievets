package com.processing.apitests.gateway;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.ServiceUrls;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.Waits;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Gateway: health-check, маршрутизация, rate-limit.
 * Источники: docs/api-spec.md §1, tz/02-gateway.md §1, §3, §4; services/gateway/.../application.yml.
 */
@DisplayName("Gateway: health, маршруты, rate-limit")
class GatewayApiTest extends ApiTestBase {

    @Test
    @DisplayName("GET /health → 200, статус ok и статусы 4 downstream-сервисов (api-spec §1)")
    void healthReportsAllDownstreamServices() {
        given()
                .spec(Specs.gateway())
        .when()
                .get(Endpoints.HEALTH)
        .then()
                .statusCode(200)
                .contentType(containsString("application/json"))
                .body(matchesJsonSchemaInClasspath("schemas/gateway-health.json"))
                .body("status", equalTo("ok"))
                .body("service", equalTo("gateway"))
                .body("version", equalTo("1.0.0"))
                .body("services.keySet()", hasSize(4))
                .body("services.values()", everyItem(equalTo("ok")));
    }

    @Test
    @DisplayName("X-Request-Id клиента пробрасывается и возвращается в ответе проксируемого запроса (tz/02 §6)")
    void requestIdIsPropagatedThroughProxiedRoute() {
        // given
        String requestId = "api-test-trace-" + System.nanoTime();

        // when / then — RequestLoggingFilter переиспользует входящий X-Request-Id
        given()
                .baseUri(ServiceUrls.GATEWAY)
                .header("X-Request-Id", requestId)
        .when()
                .get(Endpoints.DASHBOARD_STATS)
        .then()
                .statusCode(200)
                .header("X-Request-Id", equalTo(requestId));
    }

    @Test
    @DisplayName("Без входящего X-Request-Id Gateway генерирует свой (UUID) для проксируемого запроса")
    void requestIdIsGeneratedWhenAbsent() {
        given()
                .baseUri(ServiceUrls.GATEWAY)
        .when()
                .get(Endpoints.DASHBOARD_STATS)
        .then()
                .statusCode(200)
                .header("X-Request-Id", notNullValue());
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-24] X-Request-Id в ответе на /health (tz/02 §6: «каждый запрос»); факт: заголовка нет")
    void healthResponseCarriesRequestId() {
        given()
                .baseUri(ServiceUrls.GATEWAY)
        .when()
                .get(Endpoints.HEALTH)
        .then()
                .header("X-Request-Id", notNullValue());
    }

    @Test
    @DisplayName("Путь без маршрута → 404 от самого Gateway (тело с path, без serviceName downstream)")
    void unknownRouteIsRejectedByGateway() {
        given()
                .spec(Specs.gateway())
        .when()
                .get("/api/no-such-route")
        .then()
                .statusCode(404)
                .body("status", equalTo(404))
                .body("path", equalTo("/api/no-such-route"))
                .body("error", equalTo("Not Found"));
    }

    @Test
    @DisplayName("GET /api/transactions не маршрутизируется в Switch (только POST) → 404 от Logger")
    void getTransactionsIsNotRoutedToSwitch() {
        // Маршрут switch: Path=/api/transactions + Method=POST. GET попадает в маршрут logger (/api/transactions/**),
        // где такого ресурса нет. Принадлежность отказа видна по serviceName.
        given()
                .spec(Specs.gateway())
        .when()
                .get(Endpoints.TRANSACTIONS)
        .then()
                .statusCode(404)
                .body("serviceName", equalTo("transaction-logger"))
                .body("error", equalTo("Not found"));
    }

    @Test
    @DisplayName("Rate-limit: > 100 запросов/с на POST /api/transactions → 429, Retry-After: 1 (tz/02 §4)")
    void rateLimitRejectsBurstWith429() throws Exception {
        // given: невалидное тело — запросы, прошедшие лимит, отсекаются валидацией (400) и не создают транзакций.
        // Rate-limit фильтр стоит раньше валидации (order HIGHEST_PRECEDENCE+1 против +2).
        int requests = 400;
        ExecutorService pool = Executors.newFixedThreadPool(40);
        List<Future<Response>> futures = new ArrayList<>();

        // when
        try {
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(() -> given()
                        .spec(Specs.gateway())
                        .body("{}")
                        .post(Endpoints.TRANSACTIONS)));
            }
            List<Response> limited = new ArrayList<>();
            for (Future<Response> future : futures) {
                Response response = future.get();
                if (response.statusCode() == 429) {
                    limited.add(response);
                }
            }

            // then
            assertFalse(limited.isEmpty(), "ни один из " + requests + " запросов не получил 429");
            limited.get(0).then()
                    .header("Retry-After", equalTo("1"))
                    .contentType(containsString("application/json"))
                    .body("error", equalTo("RATE_LIMIT_EXCEEDED"))
                    .body("message", equalTo("Too many requests. Try again later."))
                    .body("retryAfterMs", equalTo(1000))
                    // [DISC-27, ограничение] openapi ErrorResponse требует timestamp — в теле 429 его нет;
                    // фиксируем наблюдаемый состав тела (tz/02 §4 приводит именно такой пример).
                    .body("keySet()", hasSize(3));
        } finally {
            pool.shutdownNow();
        }

        // then: через окно Retry-After бакет восстанавливается — 429 больше нет
        Waits.untilDelivered().untilAsserted(() ->
                given()
                        .spec(Specs.gateway())
                        .body("{}")
                .when()
                        .post(Endpoints.TRANSACTIONS)
                .then()
                        .statusCode(not(equalTo(429))));
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-20] GET /api/simulator/merchants через Gateway → 200 (openapi), факт: 404 — маршрута нет")
    void merchantsListIsReachableThroughGateway() {
        given()
                .spec(Specs.gateway())
        .when()
                .get(Endpoints.MERCHANTS)
        .then()
                .statusCode(200);
    }
}
