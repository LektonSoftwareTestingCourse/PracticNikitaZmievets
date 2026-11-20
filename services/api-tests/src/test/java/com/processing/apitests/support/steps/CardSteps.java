package com.processing.apitests.support.steps;

import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.data.CardRequestBuilder;
import io.restassured.path.json.JsonPath;

import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Подготовка состояния карт только через API (без доступа к БД).
 *
 * <p>Создание и изменение идут через Gateway — это публичный контракт. Чтение баланса для проверок —
 * напрямую из Card Management, потому что Gateway кэширует GET /api/cards/** и после резервирования
 * (которое идёт в обход Gateway) отдаёт устаревшие данные — см. DISC-05 в отчёте.</p>
 */
public final class CardSteps {

    private CardSteps() {
    }

    /** Создаёт карту и возвращает ответ 201 целиком. */
    public static JsonPath create(CardRequestBuilder card) {
        return given()
                .spec(Specs.gateway())
                .body(card.build())
                .when()
                .post(Endpoints.CARDS)
                .then()
                .statusCode(201)
                .extract().jsonPath();
    }

    /** ACTIVE-карта с явно заданными балансом и лимитами; возвращает PAN. */
    public static String activeCard(long balance, long dailyLimit, long monthlyLimit) {
        return create(CardRequestBuilder.aCard()
                .balance(balance)
                .dailyLimit(dailyLimit)
                .monthlyLimit(monthlyLimit))
                .getString("pan");
    }

    /** ACTIVE-карта с лимитами по умолчанию (15 000 000 / 300 000 000). */
    public static String activeCard(long balance) {
        return activeCard(balance, CardRequestBuilder.DEFAULT_DAILY_LIMIT, CardRequestBuilder.DEFAULT_MONTHLY_LIMIT);
    }

    public static void patch(String pan, Map<String, Object> changes) {
        given()
                .spec(Specs.gateway())
                .body(changes)
                .when()
                .patch(Endpoints.CARD, pan)
                .then()
                .statusCode(200);
    }

    public static void setStatus(String pan, String status) {
        patch(pan, Map.of("status", status));
    }

    public static void delete(String pan) {
        given()
                .spec(Specs.gateway())
                .when()
                .delete(Endpoints.CARD, pan)
                .then()
                .statusCode(204);
    }

    /** Карта, прочитанная из Card Management напрямую (без кэша Gateway). */
    public static JsonPath read(String pan) {
        return given()
                .spec(Specs.cardManagement())
                .when()
                .get(Endpoints.CARD, pan)
                .then()
                .statusCode(200)
                .extract().jsonPath();
    }

    public static long balance(String pan) {
        return read(pan).getLong("availableBalance");
    }
}
