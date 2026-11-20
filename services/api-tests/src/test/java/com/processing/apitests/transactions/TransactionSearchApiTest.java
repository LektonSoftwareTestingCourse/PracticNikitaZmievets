package com.processing.apitests.transactions;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestIds;
import com.processing.apitests.support.data.TransactionRecordBuilder;
import com.processing.apitests.support.steps.TransactionSteps;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GET /api/transactions/search и /api/transactions/export через Gateway.
 *
 * <p>Источники: docs/api-spec.md §3 (параметры, limit по умолчанию 50, offset 0); openapi /api/transactions/search
 * (limit 1..500, offset ≥ 0, нормализация declineReason); tz/08 §3 (AND-логика фильтров).</p>
 *
 * <p>Данные: 8 записей журнала с уникальным для прогона merchantId создаются через POST /api/internal/log —
 * поля полностью контролируются, поэтому ожидаемое количество вычисляется оракулом ({@link #expectedCount})
 * по этому же набору, а не берётся «из ответа». Каждый запрос ограничен merchantId набора — чужие записи
 * в общей БД не влияют на результат. Тесты класса только читают набор.</p>
 */
@DisplayName("Поиск и экспорт транзакций: фильтры и пагинация")
class TransactionSearchApiTest extends ApiTestBase {

    private static final String MERCHANT = TestIds.merchantId();
    private static final List<Seed> DATASET = List.of(
            new Seed("2025-03-01", "APPROVED", "5411", "ISS001", null),
            new Seed("2025-03-01", "DECLINED", "5812", "ISS002", "INSUFFICIENT_FUNDS"),
            new Seed("2025-03-02", "APPROVED", "5812", "ISS001", null),
            new Seed("2025-03-02", "DECLINED", "5411", "ISS002", "CARD_BLOCKED"),
            new Seed("2025-03-03", "APPROVED", "5411", "ISS002", null),
            new Seed("2025-03-03", "DECLINED", "5411", "ISS001", "EXCEEDS_AMOUNT_LIMIT"),
            new Seed("2025-03-04", "DECLINED", "5812", "ISS001", "INSUFFICIENT_FUNDS"),
            new Seed("2025-03-04", "APPROVED", "5812", "ISS002", null));
    private static final List<TransactionRecordBuilder> RECORDS = new ArrayList<>();

    @BeforeAll
    static void seedJournal() {
        for (Seed seed : DATASET) {
            TransactionRecordBuilder record = "APPROVED".equals(seed.status())
                    ? TransactionRecordBuilder.approved()
                    : TransactionRecordBuilder.declined(seed.reason());
            record.with("merchantId", MERCHANT)
                    .with("mcc", seed.mcc())
                    .with("issuerId", seed.issuerId())
                    .with("transmissionDateTime", seed.date() + "T10:00:00Z");
            TransactionSteps.store(record);
            RECORDS.add(record);
        }
    }

    private static RequestSpecification searchInDataset() {
        return given().spec(Specs.gateway()).queryParam("merchantId", MERCHANT);
    }

    // ---------- Каждый параметр по отдельности ----------

    @Test
    @DisplayName("Без доп. фильтров: все 8 записей набора, схема ответа соблюдена")
    void merchantFilterReturnsWholeDataset() {
        searchInDataset()
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/transaction-search.json"))
                .body("total", equalTo(DATASET.size()))
                .body("transactions", hasSize(DATASET.size()))
                .body("transactions.merchantId", everyItem(equalTo(MERCHANT)));
    }

    @Test
    @DisplayName("pan: точное совпадение — ровно одна запись этого PAN")
    void panFilterReturnsSingleRecord() {
        TransactionRecordBuilder target = RECORDS.get(3);

        searchInDataset()
                .queryParam("pan", target.get("pan"))
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(200)
                .body("total", equalTo(1))
                .body("transactions[0].id", equalTo(target.id()));
    }

    @ParameterizedTest(name = "status={0} → {1} записей")
    @CsvSource({"APPROVED, 4", "DECLINED, 4"})
    @DisplayName("status: APPROVED / DECLINED (GPath: каждая запись выборки имеет запрошенный статус)")
    void statusFilter(String status, int expected) {
        searchInDataset()
                .queryParam("status", status)
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(200)
                .body("total", equalTo(expected))
                .body("transactions.findAll { it.status == '" + status + "' }", hasSize(expected))
                .body("transactions.findAll { it.status != '" + status + "' }", hasSize(0));
    }

    @ParameterizedTest(name = "dateFrom={0}, dateTo={1} → {2}")
    @CsvSource({
        "2025-03-01, 2025-03-01, 2",
        "2025-03-02, 2025-03-03, 4",
        "2025-03-03,           , 4",
        "          , 2025-03-01, 2",
        "2025-03-05,           , 0",
        "          , 2025-02-28, 0"
    })
    @DisplayName("dateFrom/dateTo: обе границы включительно (openapi: dateTo — «до конца суток»)")
    void dateRangeFilter(String dateFrom, String dateTo, int expected) {
        RequestSpecification request = searchInDataset();
        if (dateFrom != null) {
            request.queryParam("dateFrom", dateFrom);
        }
        if (dateTo != null) {
            request.queryParam("dateTo", dateTo);
        }

        request
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(200)
                .body("total", equalTo(expected))
                .body("transactions", hasSize(expected));
    }

    @ParameterizedTest(name = "declineReason=''{0}'' → {1}")
    @CsvSource({
        "INSUFFICIENT_FUNDS, 2",
        "insufficient_funds, 2",
        "insufficient funds, 2",
        "funds,              2",
        "card blocked,       1",
        "card,               1",
        "stolen,             0"
    })
    @DisplayName("declineReason: полнотекстовый поиск, '_' и '-' = пробел, регистр не важен (openapi)")
    void declineReasonFullTextFilter(String query, int expected) {
        searchInDataset()
                .queryParam("declineReason", query)
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(200)
                .body("total", equalTo(expected))
                .body("transactions.status", everyItem(equalTo("DECLINED")));
    }

    // ---------- Попарные комбинации ----------

    @ParameterizedTest(name = "[{index}] status={0} mcc={1} issuer={2} dates={3}..{4}")
    @CsvSource({
        // Ортогональный массив L9(3^4): 4 параметра × 3 значения (значение / другое значение / не задан).
        // Все пары значений любых двух параметров встречаются хотя бы раз; 9 строк вместо 81 полного перебора.
        "APPROVED, 5411, ISS001, 2025-03-01, 2025-03-02",
        "APPROVED, 5812, ISS002, 2025-03-03, 2025-03-04",
        "APPROVED,     ,       ,           ,           ",
        "DECLINED, 5411, ISS002,           ,           ",
        "DECLINED, 5812,       , 2025-03-01, 2025-03-02",
        "DECLINED,     , ISS001, 2025-03-03, 2025-03-04",
        "        , 5411,       , 2025-03-03, 2025-03-04",
        "        , 5812, ISS001,           ,           ",
        "        ,     , ISS002, 2025-03-01, 2025-03-02"
    })
    @DisplayName("Комбинации фильтров (AND-логика, tz/08 §3): попарное покрытие")
    void pairwiseFilterCombinations(String status, String mcc, String issuerId, String dateFrom, String dateTo) {
        // given
        int expected = expectedCount(status, mcc, issuerId, dateFrom, dateTo);
        RequestSpecification request = searchInDataset();
        addIfPresent(request, "status", status);
        addIfPresent(request, "mcc", mcc);
        addIfPresent(request, "issuerId", issuerId);
        addIfPresent(request, "dateFrom", dateFrom);
        addIfPresent(request, "dateTo", dateTo);

        // when / then
        request
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(200)
                .body("total", equalTo(expected))
                .body("transactions", hasSize(expected));
    }

    // ---------- Пустая выборка и некорректные комбинации ----------

    @Test
    @DisplayName("Пустая выборка: 200, total = 0, transactions = [] (не 404)")
    void emptySelection() {
        given()
                .spec(Specs.gateway())
                .queryParam("merchantId", TestIds.merchantId())
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(200)
                .body("total", equalTo(0))
                .body("transactions", hasSize(0));
    }

    @Test
    @DisplayName("Некорректная комбинация dateFrom > dateTo → 400 Validation error от transaction-logger")
    void invertedDateRangeIsRejected() {
        searchInDataset()
                .queryParam("dateFrom", "2025-03-04")
                .queryParam("dateTo", "2025-03-01")
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("serviceName", equalTo("transaction-logger"));
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-14] dateFrom > dateTo: сообщение называет правило; факт: message = \"\"")
    void invertedDateRangeMessageNamesTheRule() {
        searchInDataset()
                .queryParam("dateFrom", "2025-03-04")
                .queryParam("dateTo", "2025-03-01")
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(400)
                .body("message", containsString("dateFrom must not be after dateTo"));
    }

    @ParameterizedTest(name = "{0}={1} → 400, сообщение про {0}")
    @CsvSource({
        "status,   CANCELLED",
        "dateFrom, 2025-13-01",
        "dateTo,   01.03.2025",
        "limit,    abc"
    })
    @DisplayName("Недопустимое значение параметра → 400, из сообщения видно поле")
    void invalidParameterValueIsRejected(String parameter, String value) {
        searchInDataset()
                .queryParam(parameter, value)
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", containsString(parameter + ":"));
    }

    // ---------- Пагинация ----------

    @ParameterizedTest(name = "limit={0}, offset={1} → {2} записей на странице, total=8")
    @CsvSource({
        "1,   0, 1",
        "3,   0, 3",
        "3,   6, 2",
        "3,   8, 0",
        "500, 0, 8"
    })
    @DisplayName("limit/offset: размер страницы, хвост, смещение за конец; total не зависит от страницы")
    void pagination(int limit, int offset, int expectedOnPage) {
        searchInDataset()
                .queryParam("limit", limit)
                .queryParam("offset", offset)
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(200)
                .body("total", equalTo(DATASET.size()))
                .body("transactions", hasSize(expectedOnPage));
    }

    @ParameterizedTest(name = "{0}={1} → 400")
    @CsvSource({
        "limit,  0,  limit: must be greater than 0",
        "limit,  501, limit: limit must not exceed 500",
        "offset, -1, offset: must be greater than or equal to 0"
    })
    @DisplayName("Границы пагинации: limit ∈ [1, 500], offset ≥ 0 (openapi)")
    void paginationOutOfBoundsIsRejected(String parameter, int value, String message) {
        searchInDataset()
                .queryParam(parameter, value)
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(400)
                .body("message", equalTo(message));
    }

    @Test
    @DisplayName("limit по умолчанию = 50 (api-spec §3): страница без limit не больше 50 записей")
    void defaultLimitIsFifty() {
        // when
        JsonPath page = given()
                .spec(Specs.gateway())
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(200)
                .body("transactions.size()", lessThanOrEqualTo(50))
                .extract().jsonPath();

        // then: страница полная, пока записей хватает (журнал общий, поэтому сравниваем с min(total, 50))
        assertEquals(Math.min(page.getInt("total"), 50), page.getList("transactions").size());
    }

    @ParameterizedTest(name = "pan=''{0}''")
    @ValueSource(strings = {"abc", "123"})
    @DisplayName("[DISC-15, ограничение] pan не по шаблону ^\\d{16}$ не валидируется: 200 и пустая выборка")
    void malformedPanFilterYieldsEmptySelection(String pan) {
        searchInDataset()
                .queryParam("pan", pan)
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(200)
                .body("total", equalTo(0));
    }

    // ---------- Экспорт ----------

    @Test
    @DisplayName("GET /api/transactions/export: text/csv, вложение transactions.csv, limit не применяется")
    void exportReturnsCsvAttachment() {
        // when
        String csv = searchInDataset()
                .queryParam("limit", 1)
        .when()
                .get(Endpoints.TRANSACTIONS_EXPORT)
        .then()
                .statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", equalTo("attachment; filename=\"transactions.csv\""))
                .body(not(emptyString()))
                .body(startsWith("id,mti,stan,rrn,pan"))
                .extract().asString();

        // then: все 8 записей набора, хотя limit=1 (openapi: «Параметры limit/offset не применяются»)
        RECORDS.forEach(record -> assertThat(csv, containsString(record.id())));
    }

    // ---------- Оракул ----------

    private static int expectedCount(String status, String mcc, String issuerId, String dateFrom, String dateTo) {
        return (int) DATASET.stream()
                .filter(seed -> status == null || seed.status().equals(status))
                .filter(seed -> mcc == null || seed.mcc().equals(mcc))
                .filter(seed -> issuerId == null || seed.issuerId().equals(issuerId))
                .filter(seed -> dateFrom == null || !LocalDate.parse(seed.date()).isBefore(LocalDate.parse(dateFrom)))
                .filter(seed -> dateTo == null || !LocalDate.parse(seed.date()).isAfter(LocalDate.parse(dateTo)))
                .count();
    }

    private static void addIfPresent(RequestSpecification request, String name, String value) {
        if (Objects.nonNull(value)) {
            request.queryParam(name, value);
        }
    }

    private record Seed(String date, String status, String mcc, String issuerId, String reason) {
    }
}
