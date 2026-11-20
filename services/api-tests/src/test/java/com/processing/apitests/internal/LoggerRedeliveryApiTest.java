package com.processing.apitests.internal;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Formats;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestIds;
import com.processing.apitests.support.data.TransactionRecordBuilder;
import io.restassured.response.ValidatableResponse;
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
import static org.hamcrest.Matchers.matchesPattern;

/**
 * Повторная доставка одного и того же сообщения в Transaction Logger: POST /api/internal/log.
 *
 * <p>Источники: tz/08-transaction-logger.md §2 «Идемпотентность» (повтор с тем же id — не дубликат, HTTP 200
 * с существующей записью); openapi /api/internal/log (201 и 200); InternalTransactionController (409 — тот же id,
 * другие данные); TransactionMapper.matches (createdAt в сравнении не участвует).</p>
 *
 * <p>Исходы: (1) первая доставка → 201; (2) повтор с идентичными данными → 200 и та же запись;
 * (3) повтор, отличающийся только createdAt (Switch пересоздал сообщение при retry) → 200;
 * (4) совпадение ключа id при расхождении бизнес-данных → 409; во всех случаях запись в журнале одна.</p>
 *
 * <p>Мутационная проверка: при отключённой дедупликации в TransactionService.store тест повтора
 * становится красным — см. отчёт, раздел 6.</p>
 */
@DisplayName("POST /api/internal/log: повторная доставка")
class LoggerRedeliveryApiTest extends ApiTestBase {

    private static ValidatableResponse deliver(Map<String, Object> record) {
        return given()
                .spec(Specs.logger())
                .body(record)
        .when()
                .post(Endpoints.INTERNAL_LOG)
        .then();
    }

    private static void assertSingleRecord(String merchantId) {
        given()
                .spec(Specs.gateway())
                .queryParam("merchantId", merchantId)
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(200)
                .body("total", equalTo(1));
    }

    @Test
    @DisplayName("Первая доставка → 201 {id, status: stored} (tz/08 §2 п.4)")
    void firstDeliveryIsStored() {
        TransactionRecordBuilder record = TransactionRecordBuilder.approved();

        deliver(record.build())
                .statusCode(201)
                .body("id", equalTo(record.id()))
                .body("id", matchesPattern(Formats.UUID))
                .body("status", equalTo("stored"));
    }

    @Test
    @DisplayName("Повтор с идентичными данными → 200 и существующая запись, дубликата нет")
    void identicalRedeliveryReturnsExistingRecord() {
        // given
        TransactionRecordBuilder record = TransactionRecordBuilder.approved();
        deliver(record.build()).statusCode(201);

        // when
        ValidatableResponse redelivery = deliver(record.build());

        // then
        redelivery
                .statusCode(200)
                .body("id", equalTo(record.id()))
                .body("stan", equalTo(record.get("stan")))
                .body("rrn", equalTo(record.get("rrn")));
        assertSingleRecord((String) record.get("merchantId"));
    }

    @Test
    @DisplayName("Повтор, отличающийся только createdAt → 200 (createdAt не участвует в сравнении)")
    void redeliveryWithDifferentCreatedAtIsIdempotent() {
        TransactionRecordBuilder record = TransactionRecordBuilder.approved();
        deliver(record.build()).statusCode(201);

        deliver(record.with("createdAt", "2020-01-01T00:00:00Z").build())
                .statusCode(200)
                .body("id", equalTo(record.id()));
        assertSingleRecord((String) record.get("merchantId"));
    }

    @ParameterizedTest(name = "тот же id, другое поле {0} = {1} → 409")
    @CsvSource({
        "amount,        999",
        "status,        DECLINED",
        "rrn,           000000000001",
        "authCode,      ZZZ999",
        "issuerId,      ISS005",
        "processingTimeMs, 77"
    })
    @DisplayName("Совпадение ключа при расхождении данных → 409 Transaction conflict, исходная запись не изменена")
    void sameIdWithDifferentDataIsConflict(String field, String value) {
        // given
        TransactionRecordBuilder record = TransactionRecordBuilder.approved();
        deliver(record.build()).statusCode(201);
        Object original = record.get(field);

        // when
        ValidatableResponse conflict = deliver(record.with(field, value).build());

        // then
        conflict
                .statusCode(409)
                .body("error", equalTo("Transaction conflict"))
                .body("message", equalTo("Transaction with id " + record.id() + " already exists with different data"))
                .body("serviceName", equalTo("transaction-logger"));
        // исходная запись не перезаписана
        given()
                .spec(Specs.gateway())
                .queryParam("merchantId", record.get("merchantId"))
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .body("total", equalTo(1))
                .body("transactions[0]." + field, equalTo(original));
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-16] Повтор → 200 LogResponse {id, status: already_exists} (openapi); факт: полная Transaction")
    void redeliveryAnswersWithLogResponse() {
        TransactionRecordBuilder record = TransactionRecordBuilder.approved();
        deliver(record.build()).statusCode(201);

        deliver(record.build())
                .statusCode(200)
                .body("status", equalTo("already_exists"));
    }

    // ---------- Валидация тела записи ----------

    @ParameterizedTest(name = "без поля {0} → 400")
    @ValueSource(strings = {"id", "stan", "pan", "amount", "status", "transmissionDateTime", "createdAt"})
    @DisplayName("Обязательные поля записи (common TransactionRequest: @NotNull/@NotBlank)")
    void recordWithoutRequiredFieldIsRejected(String field) {
        deliver(TransactionRecordBuilder.approved().without(field).build())
                .statusCode(400)
                .body("error", equalTo("Validation error"))
                .body("message", containsString(field + ": must not be"));
    }

    @ParameterizedTest(name = "{0} = {1} → 400 «{2}»")
    @CsvSource({
        "stan,         12345,              stan: stan must be exactly 6 digits",
        "pan,          4000,               pan: PAN number must contain exactly 16 digits",
        "mcc,          54A1,               mcc: mcc must be exactly 4 digits",
        "currencyCode, RUB,                currencyCode: currencyCode must be exactly 3 digits",
        "acquirerId,   ACQ00000001,        acquirerId: size must be between 0 and 10"
    })
    @DisplayName("Формат полей записи → 400 с указанием поля и правила")
    void recordWithInvalidFieldFormatIsRejected(String field, String value, String message) {
        deliver(TransactionRecordBuilder.approved().with(field, value).build())
                .statusCode(400)
                .body("message", equalTo(message));
    }

    @Test
    @DisplayName("amount = 0 → 400 (amount @Positive), неизвестный status → 400 Invalid request body")
    void recordWithNonPositiveAmountOrUnknownStatusIsRejected() {
        deliver(TransactionRecordBuilder.approved().with("amount", 0).build())
                .statusCode(400)
                .body("message", equalTo("amount: must be greater than 0"));
        deliver(TransactionRecordBuilder.approved().with("status", "REVERSED").build())
                .statusCode(400)
                .body("error", equalTo("Invalid request body"));
    }

    @Test
    @DisplayName("Повтор невалидного сообщения не создаёт записи: оба раза 400")
    void invalidRecordIsNeverStoredOnRedelivery() {
        String merchantId = TestIds.merchantId();
        Map<String, Object> invalid = TransactionRecordBuilder.approved()
                .with("merchantId", merchantId).with("stan", "1").build();

        deliver(invalid).statusCode(400);
        deliver(invalid).statusCode(400);

        given()
                .spec(Specs.gateway())
                .queryParam("merchantId", merchantId)
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .body("total", equalTo(0));
    }
}
