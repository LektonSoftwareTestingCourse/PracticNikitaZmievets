package com.processing.apitests.transactions;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Decline;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestIds;
import com.processing.apitests.support.Waits;
import com.processing.apitests.support.data.AuthorizationRequestBuilder;
import com.processing.apitests.support.steps.CardSteps;
import com.processing.apitests.support.steps.TransactionSteps;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Асинхронный эффект транзакции: запись в журнал Transaction Logger (Switch → RabbitMQ → Logger).
 *
 * <p>Источники: docs/architecture.md «Путь транзакции» и «Вариант A» (публикация в smp.transactions,
 * потребитель transaction-log); tz/08-transaction-logger.md §2–3.</p>
 *
 * <p>Появление ожидается через Awaitility (без фиксированных пауз). Отсутствие проверяется отдельно —
 * условие «записи нет» должно держаться на всём окне наблюдения (Awaitility during), потому что
 * однократная проверка «не нашли» прошла бы и тогда, когда запись просто ещё не доставлена.</p>
 */
@DisplayName("Журнал транзакций: появление и отсутствие асинхронного эффекта")
class TransactionJournalApiTest extends ApiTestBase {

    @Test
    @DisplayName("Сквозная цепочка: Gateway → Switch → Authorization → RabbitMQ → Logger → поиск через Gateway")
    void approvedTransactionReachesJournalAndIsSearchable() {
        // given
        String pan = CardSteps.activeCard(1_000_000);
        AuthorizationRequestBuilder request = AuthorizationRequestBuilder.purchase(pan, 150_000);

        // when
        JsonPath decision = TransactionSteps.authorize(request);

        // then: запись появилась (асинхронно) и совпадает с решением и запросом
        Waits.untilDelivered().untilAsserted(() ->
                TransactionSteps.searchByPan(pan).body("total", equalTo(1)));

        given()
                .spec(Specs.gateway())
                .queryParam("pan", pan)
        .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
        .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/transaction-search.json"))
                .body("transactions", hasSize(1))
                // GPath: проверка привязана к записи этого теста (по STAN), а не к выборке в целом
                .body("transactions.find { it.stan == '" + request.stan() + "' }.rrn", equalTo(decision.getString("rrn")))
                .body("transactions.find { it.stan == '" + request.stan() + "' }.authCode",
                        equalTo(decision.getString("authCode")))
                .body("transactions[0].status", equalTo("APPROVED"))
                .body("transactions[0].amount", equalTo(150_000))
                .body("transactions[0].merchantId", equalTo(request.merchantId()))
                // issuerId проставляет Switch по BIN (switch application.yml bin-routing 400000 → ISS001)
                .body("transactions[0].issuerId", equalTo("ISS001"))
                .body("transactions[0].declineReason", nullValue());
    }

    @Test
    @DisplayName("Отказ тоже журналируется: DECLINED, причина, rrn = null")
    void declinedTransactionIsJournaled() {
        String pan = CardSteps.activeCard(1_000);
        AuthorizationRequestBuilder request = AuthorizationRequestBuilder.purchase(pan, 1_001);
        TransactionSteps.authorize(request);

        Waits.untilDelivered().untilAsserted(() ->
                TransactionSteps.searchByPan(pan)
                        .body("total", equalTo(1))
                        .body("transactions[0].status", equalTo("DECLINED"))
                        .body("transactions[0].declineReason", equalTo(Decline.INSUFFICIENT_FUNDS.reason()))
                        .body("transactions[0].rrn", nullValue())
                        .body("transactions[0].stan", equalTo(request.stan())));
    }

    @Test
    @DisplayName("Отсутствие эффекта: запрос, отклонённый валидацией Gateway, не попадает в журнал и не списывает средства")
    void rejectedRequestNeverReachesJournal() {
        // given: существующая карта, но запрос невалиден (mcc из 3 цифр) — до Switch он дойти не должен
        String pan = CardSteps.activeCard(1_000_000);

        // when
        given()
                .spec(Specs.gateway())
                .body(AuthorizationRequestBuilder.purchase(pan, 100_000).with("mcc", "541").build())
        .when()
                .post(Endpoints.TRANSACTIONS)
        .then()
                .statusCode(400);

        // then: записи нет на всём окне наблюдения; баланс не тронут
        Waits.duringAbsenceWindow().until(() -> TransactionSteps.countByPan(pan) == 0);
        assertEquals(1_000_000, CardSteps.balance(pan));
    }

    @Test
    @DisplayName("Отсутствие эффекта после отката: authorize + rollback → баланс восстановлен, повторного списания нет")
    void rolledBackReservationDoesNotChargeCard() {
        // given: резерв через Authorization напрямую (внутренний вызов, как его делает Switch)
        String pan = CardSteps.activeCard(1_000_000);
        String rrn = given()
                .spec(Specs.authorization())
                .body(AuthorizationRequestBuilder.purchase(pan, 300_000).issuerId("ISS001").build())
        .when()
                .post(Endpoints.INTERNAL_AUTHORIZE)
        .then()
                .statusCode(200)
                .extract().path("rrn");

        // when: откат (architecture.md, Publisher Confirms: reversal при недоступности брокера)
        given()
                .spec(Specs.authorization())
                .body(Map.of("rrn", rrn, "pan", pan, "amount", 300_000))
        .when()
                .post(Endpoints.INTERNAL_ROLLBACK)
        .then()
                .statusCode(200)
                .body("status", equalTo("APPROVED"));

        // then: баланс вернулся и остаётся неизменным на окне наблюдения
        Waits.duringAbsenceWindow().until(() -> CardSteps.balance(pan) == 1_000_000);
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-01] APPROVED с acquirerId из 11 символов должна попасть в журнал; факт: средства списаны, записи нет")
    void approvedTransactionWithLongAcquirerIdIsJournaled() {
        // Gateway и Authorization не ограничивают длину acquirerId, а колонка transactions.acquirer_id — varchar(10):
        // Logger падает на INSERT, сообщение уходит в DLQ. Контракт architecture.md: «ни одна транзакция
        // не будет APPROVED без записи».
        String pan = CardSteps.activeCard(1_000_000);
        AuthorizationRequestBuilder request = AuthorizationRequestBuilder.purchase(pan, 100_000)
                .with("acquirerId", "ACQ" + TestIds.digits(8));
        TransactionSteps.authorize(request);

        Waits.untilDelivered().untilAsserted(() ->
                TransactionSteps.searchByPan(pan).body("total", equalTo(1)));
    }
}
