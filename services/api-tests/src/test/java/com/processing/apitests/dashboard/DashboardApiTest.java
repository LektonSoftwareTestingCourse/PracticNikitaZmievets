package com.processing.apitests.dashboard;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.data.TransactionRecordBuilder;
import com.processing.apitests.support.steps.TransactionSteps;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dashboard через Gateway: /api/dashboard/stats, /recent, /charts.
 * Источники: docs/api-spec.md §5; openapi DashboardStats, /api/dashboard/recent (limit), /api/dashboard/charts
 * (granularity hour|day); tz/08 §4–5; DashboardController (limit 1..500).
 */
@DisplayName("Dashboard: stats, recent, charts")
class DashboardApiTest extends ApiTestBase {

    private static JsonPath stats() {
        return given()
                .spec(Specs.gateway())
        .when()
                .get(Endpoints.DASHBOARD_STATS)
        .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/dashboard-stats.json"))
                .extract().jsonPath();
    }

    @Test
    @DisplayName("stats: схема и инварианты — approved + declined = total, approvalRate = approved / total")
    void statsAreConsistent() {
        JsonPath stats = stats();
        long total = stats.getLong("totalTransactions");
        long approved = stats.getLong("approvedCount");

        assertEquals(total, approved + stats.getLong("declinedCount"));
        // JsonPath разбирает дробные числа как float (~7 значащих цифр), поэтому допуск 1e-6
        assertThat(stats.getDouble("approvalRate"),
                closeTo(total == 0 ? 0 : (double) approved / total, 1e-6));
    }

    @Test
    @DisplayName("stats: новая запись журнала увеличивает totalTransactions и approvedCount")
    void statsGrowAfterNewTransaction() {
        // given
        JsonPath before = stats();

        // when
        TransactionSteps.store(TransactionRecordBuilder.approved());

        // then: журнал общий, параллельных писателей в прогоне нет, но допускаем ≥ на случай симуляторов
        JsonPath after = stats();
        assertThat(after.getLong("totalTransactions"),
                greaterThanOrEqualTo(before.getLong("totalTransactions") + 1));
        assertThat(after.getLong("approvedCount"),
                greaterThanOrEqualTo(before.getLong("approvedCount") + 1));
    }

    @Test
    @DisplayName("recent: без limit — не более 20 записей (значение по умолчанию, api-spec §5)")
    void recentDefaultsToTwenty() {
        given()
                .spec(Specs.gateway())
        .when()
                .get(Endpoints.DASHBOARD_RECENT)
        .then()
                .statusCode(200)
                .body("size()", lessThanOrEqualTo(20));
    }

    @Test
    @DisplayName("recent: только что записанная транзакция среди последних (сортировка createdAt DESC)")
    void recentContainsJustStoredTransaction() {
        TransactionRecordBuilder record = TransactionRecordBuilder.approved()
                .with("createdAt", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        TransactionSteps.store(record);

        given()
                .spec(Specs.gateway())
                .queryParam("limit", 5)
        .when()
                .get(Endpoints.DASHBOARD_RECENT)
        .then()
                .statusCode(200)
                .body("$", hasSize(5))
                .body("id", hasItem(record.id()));
    }

    @ParameterizedTest(name = "limit={0} → {1}")
    @CsvSource({"1, 200", "500, 200", "0, 400", "501, 400"})
    @DisplayName("recent: limit ∈ [1, 500] — границы ON/OFF")
    void recentLimitBoundaries(int limit, int status) {
        given()
                .spec(Specs.gateway())
                .queryParam("limit", limit)
        .when()
                .get(Endpoints.DASHBOARD_RECENT)
        .then()
                .statusCode(status);
    }

    @Test
    @DisplayName("charts: записи в изолированном часе попадают в одну корзину с верными счётчиками и суммой")
    void chartsAggregateIsolatedHour() {
        // given: 2 approved + 1 declined в случайном часе 2001 года — других записей там нет
        Instant hour = Instant.parse("2001-01-01T00:00:00Z")
                .plus(ThreadLocalRandom.current().nextInt(0, 24 * 300), ChronoUnit.HOURS);
        String createdAt = hour.plus(15, ChronoUnit.MINUTES).toString();
        TransactionSteps.store(TransactionRecordBuilder.approved().with("amount", 100).with("createdAt", createdAt));
        TransactionSteps.store(TransactionRecordBuilder.approved().with("amount", 200).with("createdAt", createdAt));
        TransactionSteps.store(TransactionRecordBuilder.declined("CARD_BLOCKED").with("amount", 300).with("createdAt", createdAt));

        // when / then
        given()
                .spec(Specs.gateway())
                .queryParam("granularity", "hour")
                .queryParam("from", hour.toString())
                .queryParam("to", hour.plus(1, ChronoUnit.HOURS).toString())
        .when()
                .get(Endpoints.DASHBOARD_CHARTS)
        .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].timestamp", equalTo(hour.toString()))
                .body("[0].total", equalTo(3))
                .body("[0].approved", equalTo(2))
                .body("[0].declined", equalTo(1))
                .body("[0].amount", equalTo(600));
    }

    @ParameterizedTest(name = "granularity={0} → 400")
    @ValueSource(strings = {"week", "HOUR", "minute"})
    @DisplayName("charts: granularity только hour|day")
    void chartsRejectUnknownGranularity(String granularity) {
        given()
                .spec(Specs.gateway())
                .queryParam("granularity", granularity)
        .when()
                .get(Endpoints.DASHBOARD_CHARTS)
        .then()
                .statusCode(400)
                .body("message", equalTo("granularity: granularity must be hour or day"));
    }

    @Test
    @DisplayName("charts: from > to → 400 (некорректная комбинация параметров)")
    void chartsRejectInvertedRange() {
        given()
                .spec(Specs.gateway())
                .queryParam("from", "2025-01-02T00:00:00Z")
                .queryParam("to", "2025-01-01T00:00:00Z")
        .when()
                .get(Endpoints.DASHBOARD_CHARTS)
        .then()
                .statusCode(400)
                .body("error", equalTo("Validation error"));
    }
}
