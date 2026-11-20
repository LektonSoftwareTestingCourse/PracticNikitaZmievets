package com.processing.apitests.internal;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Specs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Bin Lookup: GET /api/bin/{bin} — вызывается Authorization для обогащения issuerId.
 * Источник: docs/architecture.md «Вариант B»; BinLookupController (таблица BIN, параметры delay/fail).
 */
@DisplayName("Bin Lookup: /api/bin/{bin}")
class BinLookupApiTest extends ApiTestBase {

    @ParameterizedTest(name = "BIN {0} → {1}")
    @CsvSource({
        "400000, ISS001, Test Bank 1",
        "400001, ISS002, Test Bank 2",
        "400002, ISS003, Test Bank 3",
        "400003, ISS004, Test Bank 4",
        "400004, ISS005, Test Bank 5"
    })
    @DisplayName("Известный BIN → 200 и issuerId, согласованный с bin-routing Switch")
    void knownBinResolvesIssuer(String bin, String issuerId, String issuerName) {
        given()
                .spec(Specs.binLookup())
        .when()
                .get(Endpoints.BIN_LOOKUP, bin)
        .then()
                .statusCode(200)
                .body("bin", equalTo(bin))
                .body("issuerId", equalTo(issuerId))
                .body("issuerName", equalTo(issuerName));
    }

    @Test
    @DisplayName("Неизвестный BIN → 404")
    void unknownBinIsNotFound() {
        given()
                .spec(Specs.binLookup())
        .when()
                .get(Endpoints.BIN_LOOKUP, "999999")
        .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("fail=true → 500 (режим отказа для проверки graceful degradation Authorization)")
    void failModeReturns500() {
        given()
                .spec(Specs.binLookup())
                .queryParam("fail", true)
        .when()
                .get(Endpoints.BIN_LOOKUP, "400000")
        .then()
                .statusCode(500);
    }
}
