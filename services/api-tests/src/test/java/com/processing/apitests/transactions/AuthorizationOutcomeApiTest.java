package com.processing.apitests.transactions;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Decline;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Formats;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestIds;
import com.processing.apitests.support.data.AuthorizationRequestBuilder;
import com.processing.apitests.support.steps.CardSteps;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Исходы алгоритма авторизации через публичный вход POST /api/transactions.
 *
 * <p>Источники ожиданий: docs/api-spec.md «Коды ответов»; tz/04-authorization.md §2 (шаги 1–7);
 * services/authorization/.../DeclineOutcome.java (пары код/причина); docs/practice-2/test-design.md
 * (классы КЭ-01…КЭ-18 и границы ГЗ-01…ГЗ-07).</p>
 *
 * <p>Каждый тест создаёт свою карту и явно задаёт её баланс, лимиты и статус — известное начальное состояние.
 * Для каждого исхода код и причина проверяются вместе: CARD_BLOCKED и CARD_INACTIVE оба имеют код 05.</p>
 *
 * <p>Пути достижения исхода: «состояние карты» (меняем карту через PATCH) и «данные запроса»
 * (меняем сумму / дату / PAN в запросе при нормальной карте). Различие объяснено в отчёте, раздел 2.</p>
 */
@DisplayName("POST /api/transactions: исходы авторизации")
class AuthorizationOutcomeApiTest extends ApiTestBase {

    private static final long AMOUNT = 100_000;

    private static ValidatableResponse send(AuthorizationRequestBuilder request) {
        return given()
                .spec(Specs.gateway())
                .body(request.build())
        .when()
                .post(Endpoints.TRANSACTIONS)
        .then()
                .statusCode(200)
                .body(matchesJsonSchemaInClasspath("schemas/authorization-response.json"));
    }

    private static void assertDeclined(ValidatableResponse response, Decline outcome, String stan) {
        response
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(outcome.code()))
                .body("declineReason", equalTo(outcome.reason()))
                .body("stan", equalTo(stan))
                .body("rrn", nullValue())
                .body("authCode", nullValue());
    }

    // ================= APPROVED =================

    @Test
    @DisplayName("APPROVED/00: rrn и authCode по формату, declineReason = null, баланс уменьшен на сумму")
    void approvedTransactionReservesFunds() {
        // given
        String pan = CardSteps.activeCard(1_000_000);
        AuthorizationRequestBuilder request = AuthorizationRequestBuilder.purchase(pan, AMOUNT);

        // when
        ValidatableResponse response = send(request);

        // then — api-spec §3 «Ответ 200»; формат rrn/authCode — часть контракта, значение непредсказуемо
        response
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"))
                .body("stan", equalTo(request.stan()))
                .body("rrn", matchesPattern(Formats.RRN))
                .body("authCode", matchesPattern(Formats.AUTH_CODE))
                .body("$", hasKey("declineReason"))
                .body("declineReason", nullValue())
                .body("processingTimeMs", greaterThanOrEqualTo(0));
        assertEquals(1_000_000 - AMOUNT, CardSteps.balance(pan), "резерв в Card Management (tz/04 §2 шаг 7)");
    }

    // ================= CARD_NOT_FOUND (14): три пути =================

    @Test
    @DisplayName("14 CARD_NOT_FOUND, путь «данные запроса»: PAN известного BIN, карты нет → отказ Authorization")
    void unknownPanOfKnownBinIsDeclinedByAuthorization() {
        AuthorizationRequestBuilder request = AuthorizationRequestBuilder.purchase(TestIds.unknownPan("400000"), AMOUNT);

        ValidatableResponse response = send(request);

        assertDeclined(response, Decline.CARD_NOT_FOUND, request.stan());
        // Принадлежность: Authorization эхом возвращает mti запроса (0100) — см. DISC-02
        response.body("mti", equalTo("0100"));
    }

    @Test
    @DisplayName("14 CARD_NOT_FOUND, путь «данные запроса»: BIN вне таблицы маршрутизации → отказ Switch")
    void unknownBinIsDeclinedBySwitch() {
        AuthorizationRequestBuilder request = AuthorizationRequestBuilder.purchase(TestIds.unknownPan("999999"), AMOUNT);

        ValidatableResponse response = send(request);

        assertDeclined(response, Decline.CARD_NOT_FOUND, request.stan());
        // Принадлежность: ответ сформирован Switch (AuthorizationResponse.unknownBin): mti 0110 и processingTimeMs 0
        response.body("mti", equalTo("0110")).body("processingTimeMs", equalTo(0));
    }

    @Test
    @DisplayName("14 CARD_NOT_FOUND, путь «состояние карты»: карта удалена (soft delete, tz/05 §2)")
    void deletedCardIsDeclinedAsNotFound() {
        String pan = CardSteps.activeCard(1_000_000);
        CardSteps.delete(pan);
        AuthorizationRequestBuilder request = AuthorizationRequestBuilder.purchase(pan, AMOUNT);

        assertDeclined(send(request), Decline.CARD_NOT_FOUND, request.stan());
    }

    // ================= Статус карты: 05 / 05 / 54 =================

    @ParameterizedTest(name = "статус {0} → {1} {2}")
    @CsvSource({
        "BLOCKED,  05, CARD_BLOCKED",
        "INACTIVE, 05, CARD_INACTIVE",
        "EXPIRED,  54, CARD_EXPIRED"
    })
    @DisplayName("Путь «состояние карты»: статус не ACTIVE (tz/04 §2 шаг 2; один код 05 — две разные причины)")
    void nonActiveCardIsDeclined(String status, String expectedCode, String expectedReason) {
        // given
        String pan = CardSteps.activeCard(1_000_000);
        CardSteps.setStatus(pan, status);
        AuthorizationRequestBuilder request = AuthorizationRequestBuilder.purchase(pan, AMOUNT);

        // when
        ValidatableResponse response = send(request);

        // then
        response
                .body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(expectedCode))
                .body("declineReason", equalTo(expectedReason))
                .body("rrn", nullValue());
        assertEquals(1_000_000, CardSteps.balance(pan), "отказ не резервирует средства");
    }

    // ================= CARD_EXPIRED (54) по дате транзакции =================

    @Test
    @DisplayName("54 CARD_EXPIRED, путь «данные запроса»: transmissionDateTime — первая секунда после срока карты")
    void transactionAfterExpiryMonthIsDeclined() {
        // given: срок карты = текущий месяц + 3 года (tz/05 §4); последний действительный день — конец месяца срока
        String pan = CardSteps.activeCard(1_000_000);
        Instant firstInstantAfterExpiry = expiryMonthOf(pan).plusMonths(1).atDay(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        AuthorizationRequestBuilder request = AuthorizationRequestBuilder.purchase(pan, AMOUNT).at(firstInstantAfterExpiry);

        // when / then — граница ГЗ-07 (test-design): 1-е число следующего месяца → 54
        assertDeclined(send(request), Decline.CARD_EXPIRED, request.stan());
    }

    @Test
    @DisplayName("Граница срока: последняя секунда месяца срока действия → APPROVED (ГЗ-07, ON)")
    void transactionAtLastSecondOfExpiryMonthIsApproved() {
        String pan = CardSteps.activeCard(1_000_000);
        YearMonth expiry = expiryMonthOf(pan);
        Instant lastSecond = expiry.atEndOfMonth().atTime(23, 59, 59).toInstant(ZoneOffset.UTC);

        send(AuthorizationRequestBuilder.purchase(pan, AMOUNT).at(lastSecond))
                .body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00"));
    }

    // ================= INSUFFICIENT_FUNDS (51) =================

    @Test
    @DisplayName("51 INSUFFICIENT_FUNDS, путь «данные запроса»: сумма = баланс + 1 копейка (ГЗ-04, OFF)")
    void amountAboveBalanceIsDeclined() {
        String pan = CardSteps.activeCard(AMOUNT);
        AuthorizationRequestBuilder request = AuthorizationRequestBuilder.purchase(pan, AMOUNT + 1);

        assertDeclined(send(request), Decline.INSUFFICIENT_FUNDS, request.stan());
        assertEquals(AMOUNT, CardSteps.balance(pan), "баланс не изменился");
    }

    @Test
    @DisplayName("51 INSUFFICIENT_FUNDS, путь «состояние карты»: баланс уменьшен PATCH до суммы - 1")
    void balanceLoweredBelowAmountIsDeclined() {
        String pan = CardSteps.activeCard(1_000_000);
        CardSteps.patch(pan, Map.of("availableBalance", AMOUNT - 1));
        AuthorizationRequestBuilder request = AuthorizationRequestBuilder.purchase(pan, AMOUNT);

        assertDeclined(send(request), Decline.INSUFFICIENT_FUNDS, request.stan());
    }

    @Test
    @DisplayName("Граница баланса: сумма = баланс → APPROVED, остаток 0 (ГЗ-04, ON)")
    void amountEqualToBalanceIsApproved() {
        String pan = CardSteps.activeCard(AMOUNT);

        send(AuthorizationRequestBuilder.purchase(pan, AMOUNT))
                .body("status", equalTo("APPROVED"));
        assertEquals(0, CardSteps.balance(pan));
    }

    // ================= EXCEEDS_AMOUNT_LIMIT (61) =================

    @Test
    @DisplayName("61 EXCEEDS_AMOUNT_LIMIT, путь «состояние карты»: dailyLimit = сумма - 1 (ГЗ-01, OFF)")
    void amountAboveDailyLimitIsDeclined() {
        String pan = CardSteps.activeCard(1_000_000, AMOUNT - 1, 10 * AMOUNT);
        AuthorizationRequestBuilder request = AuthorizationRequestBuilder.purchase(pan, AMOUNT);

        assertDeclined(send(request), Decline.EXCEEDS_AMOUNT_LIMIT, request.stan());
        assertEquals(1_000_000, CardSteps.balance(pan));
    }

    @Test
    @DisplayName("Граница дневного лимита: сумма = dailyLimit → APPROVED (ГЗ-01, ON)")
    void amountEqualToDailyLimitIsApproved() {
        String pan = CardSteps.activeCard(1_000_000, AMOUNT, 10 * AMOUNT);

        send(AuthorizationRequestBuilder.purchase(pan, AMOUNT))
                .body("status", equalTo("APPROVED"));
    }

    @Test
    @DisplayName("61 EXCEEDS_AMOUNT_LIMIT, путь «данные запроса»: накопление за день превышает дневной лимит")
    void accumulatedDailySpendingIsDeclined() {
        // given: лимит 1000, первая покупка 600 одобрена → остаток дневного лимита 400 (ГЗ-02)
        String pan = CardSteps.activeCard(1_000_000, 1_000, 100_000);
        Instant day = Instant.now();
        send(AuthorizationRequestBuilder.purchase(pan, 600).at(day)).body("status", equalTo("APPROVED"));

        // when: вторая покупка 401 в тот же день
        AuthorizationRequestBuilder second = AuthorizationRequestBuilder.purchase(pan, 401).at(day);

        // then
        assertDeclined(send(second), Decline.EXCEEDS_AMOUNT_LIMIT, second.stan());
        // а остаток ровно 400 — проходит (граница ON)
        send(AuthorizationRequestBuilder.purchase(pan, 400).at(day)).body("status", equalTo("APPROVED"));
    }

    @Test
    @DisplayName("61 EXCEEDS_AMOUNT_LIMIT по месячному лимиту: траты в разные дни одного месяца (ГЗ-03)")
    void accumulatedMonthlySpendingIsDeclined() {
        // given: дневной 1000, месячный 1500; фиксированный прошлый месяц — дни не зависят от даты прогона
        String pan = CardSteps.activeCard(1_000_000, 1_000, 1_500);
        LocalDate firstDay = LocalDate.of(2026, 3, 10);
        send(AuthorizationRequestBuilder.purchase(pan, 1_000).at(utc(firstDay))).body("status", equalTo("APPROVED"));

        // when: на следующий день — дневной лимит свободен, но месячный остаток 500
        AuthorizationRequestBuilder overMonthly = AuthorizationRequestBuilder.purchase(pan, 501).at(utc(firstDay.plusDays(1)));

        // then
        assertDeclined(send(overMonthly), Decline.EXCEEDS_AMOUNT_LIMIT, overMonthly.stan());
        send(AuthorizationRequestBuilder.purchase(pan, 500).at(utc(firstDay.plusDays(2))))
                .body("status", equalTo("APPROVED"));
    }

    // ================= Расхождения =================

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-02] mti ответа = \"0110\" (api-spec: \"0110\" = Auth Response); факт: эхо \"0100\"")
    void responseMtiIsAuthResponse() {
        String pan = CardSteps.activeCard(1_000_000);

        send(AuthorizationRequestBuilder.purchase(pan, AMOUNT)).body("mti", equalTo("0110"));
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-12] Лимит проверяется раньше баланса (tz/04 §2: шаги 4–5 до шага 6); факт: 51 вместо 61")
    void dailyLimitIsCheckedBeforeBalance() {
        // К-1 из test-design: Б = A - 1 и дневной лимит = A - 1 → ожидается 61
        String pan = CardSteps.activeCard(AMOUNT - 1, AMOUNT - 1, 10 * AMOUNT);
        AuthorizationRequestBuilder request = AuthorizationRequestBuilder.purchase(pan, AMOUNT);

        assertDeclined(send(request), Decline.EXCEEDS_AMOUNT_LIMIT, request.stan());
    }

    private static YearMonth expiryMonthOf(String pan) {
        // фактический формат expiryDate — yyyy-MM (DISC-03), поэтому разбираем как YearMonth ISO
        return YearMonth.parse(CardSteps.read(pan).getString("expiryDate"));
    }

    private static Instant utc(LocalDate date) {
        return date.atTime(12, 0).toInstant(ZoneOffset.UTC);
    }
}
