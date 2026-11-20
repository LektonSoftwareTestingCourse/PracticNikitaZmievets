package com.processing.apitests.cards;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Formats;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.data.CardRequestBuilder;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.YearMonth;
import java.util.Map;

import static com.processing.apitests.support.LuhnMatcher.passesLuhnCheck;
import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * POST /api/cards через Gateway.
 *
 * <p>Источники: docs/api-spec.md §2 (тело и ответ 201); openapi CardCreateRequest; tz/05 §2, §4
 * (PAN = BIN + 9 цифр + Луна, срок +3 года, ACTIVE); common CreateCardRequest (@Bin, @Pattern имени,
 * @ExactSize/@DigitsOnly валюты, @NotNegative лимитов); классы КЭ-30…КЭ-49 из docs/practice-2/test-design.md.</p>
 */
@DisplayName("POST /api/cards: создание карты")
class CardCreateApiTest extends ApiTestBase {

    private static ValidatableResponse create(Map<String, Object> body) {
        return given()
                .spec(Specs.gateway())
                .body(body)
        .when()
                .post(Endpoints.CARDS)
        .then();
    }

    private static void expectValidationError(Map<String, Object> body, String message) {
        create(body)
                .statusCode(400)
                .body(matchesJsonSchemaInClasspath("schemas/error-response.json"))
                .body("error", equalTo("MethodArgumentNotValidException"))
                .body("message", equalTo(message))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    @DisplayName("201: все поля из запроса, ACTIVE, PAN = BIN + … с контрольной цифрой Луна, срок +3 года")
    void createsActiveCard() {
        // given
        Map<String, Object> body = CardRequestBuilder.aCard()
                .with("cardholderName", "IVAN IVANOV")
                .dailyLimit(5_000_000).monthlyLimit(150_000_000).balance(50_000_000).build();

        // when
        ValidatableResponse response = create(body);

        // then
        response
                .statusCode(201)
                .body(matchesJsonSchemaInClasspath("schemas/card.json"))
                .body("pan", matchesPattern(Formats.PAN))
                .body("pan", startsWith("400000"))
                .body("pan", passesLuhnCheck())
                .body("id", matchesPattern(Formats.UUID))
                .body("bin", equalTo("400000"))
                .body("cardholderName", equalTo("IVAN IVANOV"))
                .body("status", equalTo("ACTIVE"))
                .body("currencyCode", equalTo("643"))
                .body("dailyLimit", equalTo(5_000_000))
                .body("monthlyLimit", equalTo(150_000_000))
                .body("availableBalance", equalTo(50_000_000))
                .body("issuerId", equalTo("ISS001"))
                // значение срока сверяем с фактическим форматом yyyy-MM (формат MMYY — DISC-03)
                .body("expiryDate", equalTo(YearMonth.now().plusYears(3).toString()))
                .body("createdAt", matchesPattern(Formats.ISO_INSTANT));
    }

    @ParameterizedTest(name = "BIN {0} → issuerId {1}")
    @CsvSource({"400000, ISS001", "400001, ISS002", "400002, ISS003", "400003, ISS004", "400004, ISS005"})
    @DisplayName("issuerId определяется по BIN из справочника (V5.2__init_bin_issuers_table.sql)")
    void issuerIsResolvedByBin(String bin, String issuerId) {
        create(CardRequestBuilder.aCard().bin(bin).build())
                .statusCode(201)
                .body("issuerId", equalTo(issuerId))
                .body("pan", startsWith(bin));
    }

    @Test
    @DisplayName("Две карты с одинаковым телом → обе 201, PAN различаются (К-8 test-design)")
    void identicalRequestsProduceDistinctPans() {
        Map<String, Object> body = CardRequestBuilder.aCard().build();

        String first = create(body).statusCode(201).extract().path("pan");
        create(body).statusCode(201).body("pan", not(equalTo(first)));
    }

    // ---------- Валидация: по одному нарушению на тест ----------

    @ParameterizedTest(name = "bin = ''{0}'' → 400")
    @ValueSource(strings = {"40000", "4000000", "40000A"})
    @DisplayName("bin не из 6 цифр (граница 5/7, класс «не цифры»)")
    void binOfWrongFormatIsRejected(String bin) {
        expectValidationError(CardRequestBuilder.aCard().bin(bin).build(), "BIN number must contain exactly 6 digits");
    }

    @Test
    @DisplayName("bin отсутствует → 400 «must not be blank»")
    void missingBinIsRejected() {
        expectValidationError(CardRequestBuilder.aCard().without("bin").build(), "must not be blank");
    }

    @Test
    @DisplayName("bin 6 цифр, но не из справочника → 404 BinNotFoundException (КЭ-31)")
    void unregisteredBinIsNotFound() {
        create(CardRequestBuilder.aCard().bin("999999").build())
                .statusCode(404)
                .body("error", equalTo("BinNotFoundException"))
                .body("message", equalTo("BIN not found: Unknown BIN: 999999"))
                .body("serviceName", equalTo("card-management"));
    }

    @ParameterizedTest(name = "cardholderName = ''{0}'' → 400")
    @ValueSource(strings = {"ivan ivanov", "IVAN_IVANOV", "ИВАН ИВАНОВ", "IVAN1"})
    @DisplayName("cardholderName вне ^[A-Z\\s\\-.']+$ (строчные, подчёркивание, кириллица, цифры)")
    void cardholderNameOutsideAllowedAlphabetIsRejected(String name) {
        expectValidationError(CardRequestBuilder.aCard().with("cardholderName", name).build(),
                "Cardholder name can contain only uppercase letters, spaces, ', dots and -");
    }

    @Test
    @DisplayName("cardholderName 1 и 255 символов (границы ON, openapi maxLength 255) и с допустимыми знаками → 201")
    void cardholderNameBoundaryIsAccepted() {
        create(CardRequestBuilder.aCard().with("cardholderName", "A").build()).statusCode(201);
        create(CardRequestBuilder.aCard().with("cardholderName", "A".repeat(255)).build())
                .statusCode(201)
                .body("cardholderName.length()", equalTo(255));
        create(CardRequestBuilder.aCard().with("cardholderName", "O'NEIL ANNE-MARIE JR.").build()).statusCode(201);
    }

    @Test
    @DisplayName("cardholderName 256 символов (граница OFF) → 400")
    void cardholderNameAboveMaxLengthIsRejected() {
        create(CardRequestBuilder.aCard().with("cardholderName", "A".repeat(256)).build())
                .statusCode(400)
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-28] cardholderName 256 символов: ошибка называет поле и правило; факт: OutOfRetries/PanCollision")
    void cardholderNameAboveMaxLengthMessageNamesTheRule() {
        create(CardRequestBuilder.aCard().with("cardholderName", "A".repeat(256)).build())
                .statusCode(400)
                .body("error", equalTo("MethodArgumentNotValidException"))
                .body("message", containsString("255"));
    }

    @ParameterizedTest(name = "currencyCode = ''{0}'' → 400 «{1}»")
    @CsvSource({
        "64,   The length must be exactly 3 symbols",
        "6430, The length must be exactly 3 symbols",
        "RUB,  Value can contain digits only"
    })
    @DisplayName("currencyCode: длина 3 (границы 2/4) и только цифры")
    void currencyCodeViolationsAreRejected(String currencyCode, String message) {
        expectValidationError(CardRequestBuilder.aCard().with("currencyCode", currencyCode).build(), message);
    }

    @ParameterizedTest(name = "{0} = -1 → 400")
    @ValueSource(strings = {"dailyLimit", "monthlyLimit"})
    @DisplayName("Отрицательный лимит (граница 0: OFF = -1)")
    void negativeLimitIsRejected(String field) {
        expectValidationError(CardRequestBuilder.aCard().with(field, -1).build(), "Value can not be negative");
    }

    @Test
    @DisplayName("Нулевые лимиты (граница 0: ON) → 201")
    void zeroLimitsAreAccepted() {
        create(CardRequestBuilder.aCard().dailyLimit(0).monthlyLimit(0).build())
                .statusCode(201)
                .body("dailyLimit", equalTo(0))
                .body("monthlyLimit", equalTo(0));
    }

    // ---------- Расхождения ----------

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-03] expiryDate в формате MMYY (api-spec: String(4) «1228»); факт: \"2029-11\"")
    void expiryDateIsMmyy() {
        create(CardRequestBuilder.aCard().build())
                .statusCode(201)
                .body("expiryDate", matchesPattern(Formats.EXPIRY_MMYY));
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-04] Без currencyCode/лимитов/баланса → 201 с умолчаниями (openapi default); факт: 400")
    void optionalFieldsHaveDefaults() {
        create(Map.of("bin", "400000", "cardholderName", "API TEST"))
                .statusCode(201)
                .body("currencyCode", equalTo("643"))
                .body("dailyLimit", equalTo(15_000_000))
                .body("monthlyLimit", equalTo(300_000_000))
                .body("availableBalance", equalTo(100_000_000));
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-11] monthlyLimit < dailyLimit → 400 (как в PATCH); факт: 201")
    void monthlyLimitBelowDailyIsRejected() {
        create(CardRequestBuilder.aCard().dailyLimit(5_000_000).monthlyLimit(4_999_999).build())
                .statusCode(400);
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-11] Отрицательный initialBalance → 400; факт: 201, карта с балансом -5")
    void negativeInitialBalanceIsRejected() {
        create(CardRequestBuilder.aCard().balance(-5).build())
                .statusCode(400);
    }

    @Test
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-19] Нечитаемый JSON → 400; факт: 500 (HttpMessageNotReadableException падает в общий handler)")
    void malformedJsonIsBadRequest() {
        given()
                .spec(Specs.gateway())
                .body("{bad")
        .when()
                .post(Endpoints.CARDS)
        .then()
                .statusCode(400);
    }
}
