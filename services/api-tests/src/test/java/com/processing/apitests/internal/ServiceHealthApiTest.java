package com.processing.apitests.internal;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.ServiceUrls;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Health-эндпоинты внутренних сервисов, которые опрашивает Gateway (HealthService.getDownstreamServicesHealth)
 * и Authorization. Источник: tz/0X-*.md §1 каждого сервиса, контроллеры HealthController.
 */
@DisplayName("Внутренние сервисы: GET /health")
class ServiceHealthApiTest extends ApiTestBase {

    static Stream<Arguments> services() {
        return Stream.of(
                // baseUrl, ожидаемое поле service, путь к признаку зависимости, ожидаемое значение
                Arguments.of(ServiceUrls.SWITCH, "switch", "dependencies.authorization", "ok"),
                Arguments.of(ServiceUrls.AUTHORIZATION, "authorization", "dependencies.card-management", "ok"),
                Arguments.of(ServiceUrls.CARD_MANAGEMENT, "card-management", "status", "ok"),
                Arguments.of(ServiceUrls.LOGGER, "transaction-logger", "status", "ok"));
    }

    @ParameterizedTest(name = "{1}: 200, status=ok, {2}={3}")
    @MethodSource("services")
    @DisplayName("Сервис отвечает 200 и называет себя")
    void serviceIsHealthy(String baseUrl, String serviceName, String dependencyPath, String dependencyStatus) {
        given()
                .baseUri(baseUrl)
                .accept(ContentType.JSON)
        .when()
                .get(Endpoints.HEALTH)
        .then()
                .statusCode(200)
                .body("status", equalTo("ok"))
                .body("service", equalTo(serviceName))
                .body(dependencyPath, equalTo(dependencyStatus));
    }
}
