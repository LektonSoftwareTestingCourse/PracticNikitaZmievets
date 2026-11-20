package com.processing.apitests.support.steps;

import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.data.AuthorizationRequestBuilder;
import com.processing.apitests.support.data.TransactionRecordBuilder;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;

import static io.restassured.RestAssured.given;

/**
 * Шаги для транзакций: отправка через Gateway, запись в журнал через Logger, поиск.
 */
public final class TransactionSteps {

    private TransactionSteps() {
    }

    /** Отправляет авторизацию через Gateway и ожидает HTTP 200 (решение APPROVED/DECLINED в теле). */
    public static JsonPath authorize(AuthorizationRequestBuilder request) {
        return given()
                .spec(Specs.gateway())
                .body(request.build())
                .when()
                .post(Endpoints.TRANSACTIONS)
                .then()
                .statusCode(200)
                .extract().jsonPath();
    }

    /** Записывает транзакцию в журнал через POST /api/internal/log и ожидает 201. */
    public static void store(TransactionRecordBuilder record) {
        given()
                .spec(Specs.logger())
                .body(record.build())
                .when()
                .post(Endpoints.INTERNAL_LOG)
                .then()
                .statusCode(201);
    }

    /** Количество записей журнала по PAN (поиск через Gateway). */
    public static int countByPan(String pan) {
        return searchByPan(pan).extract().jsonPath().getInt("total");
    }

    public static ValidatableResponse searchByPan(String pan) {
        return given()
                .spec(Specs.gateway())
                .queryParam("pan", pan)
                .when()
                .get(Endpoints.TRANSACTIONS_SEARCH)
                .then()
                .statusCode(200);
    }
}
