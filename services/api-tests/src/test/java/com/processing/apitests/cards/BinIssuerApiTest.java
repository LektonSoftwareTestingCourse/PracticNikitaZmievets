package com.processing.apitests.cards;

import com.processing.apitests.support.ApiTestBase;
import com.processing.apitests.support.Endpoints;
import com.processing.apitests.support.Specs;
import com.processing.apitests.support.TestIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

/**
 * Справочник BIN → issuerId в Card Management: /api/bins (используется при создании карты).
 * Эндпоинт не описан в api-spec и не маршрутизируется Gateway — DISC-21; проверяется напрямую.
 * Источник: BinIssuerController, CreateBinIssuerRequest (@Bin, @IssuerId [A-Z0-9]{6,10}).
 */
@DisplayName("Card Management: справочник /api/bins")
class BinIssuerApiTest extends ApiTestBase {

    /** BIN вне диапазона 4000xx: не пересекается с рабочими BIN, уникален для теста. */
    private static String freshBin() {
        return "7" + TestIds.digits(5);
    }

    @Test
    @DisplayName("GET /api/bins → 200, содержит 5 исходных BIN")
    void listContainsSeededBins() {
        given()
                .spec(Specs.cardManagement())
        .when()
                .get(Endpoints.BINS)
        .then()
                .statusCode(200)
                .body("bin", hasItems("400000", "400001", "400002", "400003", "400004"))
                .body("find { it.bin == '400002' }.issuerId", equalTo("ISS003"));
    }

    @Test
    @DisplayName("Жизненный цикл: создать 201 → повтор 409 → прочитать 200 → удалить 204 → прочитать 404")
    void binMappingLifecycle() {
        String bin = freshBin();
        Map<String, String> mapping = Map.of("bin", bin, "issuerId", "ISSAPI1");

        given().spec(Specs.cardManagement()).body(mapping)
        .when().post(Endpoints.BINS)
        .then().statusCode(201).body("bin", equalTo(bin)).body("issuerId", equalTo("ISSAPI1"));

        given().spec(Specs.cardManagement()).body(mapping)
        .when().post(Endpoints.BINS)
        .then().statusCode(409).body("error", equalTo("BinAlreadyExistException"));

        given().spec(Specs.cardManagement())
        .when().get(Endpoints.BIN, bin)
        .then().statusCode(200).body("issuerId", equalTo("ISSAPI1"));

        given().spec(Specs.cardManagement())
        .when().delete(Endpoints.BIN, bin)
        .then().statusCode(204);

        given().spec(Specs.cardManagement())
        .when().get(Endpoints.BIN, bin)
        .then().statusCode(404).body("error", equalTo("BinNotFoundException"));
    }

    @ParameterizedTest(name = "bin={0} → 400")
    @ValueSource(strings = {"12345", "1234567", "7ABCDE"})
    @DisplayName("Невалидный bin в сопоставлении → 400 «BIN number must contain exactly 6 digits»")
    void invalidBinIsRejected(String bin) {
        given()
                .spec(Specs.cardManagement())
                .body(Map.of("bin", bin, "issuerId", "ISSAPI1"))
        .when()
                .post(Endpoints.BINS)
        .then()
                .statusCode(400)
                .body("error", equalTo("MethodArgumentNotValidException"))
                .body("message", equalTo("BIN number must contain exactly 6 digits"));
    }

    @ParameterizedTest(name = "issuerId={0} → 400")
    @ValueSource(strings = {"ISS01", "iss0001", "ISS00000001"})
    @Tag(KNOWN_DEFECT)
    @DisplayName("[DISC-21] issuerId вне [A-Z0-9]{6,10} → 400; факт: 201 (у аннотации @IssuerId нет @Constraint)")
    void invalidIssuerIdIsRejected(String issuerId) {
        String bin = freshBin();
        try {
            given()
                    .spec(Specs.cardManagement())
                    .body(Map.of("bin", bin, "issuerId", issuerId))
            .when()
                    .post(Endpoints.BINS)
            .then()
                    .statusCode(400);
        } finally {
            // справочник общий: если дефект проявился и запись создана — убираем её
            given().spec(Specs.cardManagement()).delete(Endpoints.BIN, bin);
        }
    }

    @Test
    @DisplayName("DELETE несуществующего BIN → 404")
    void deleteOfUnknownBinIsNotFound() {
        given()
                .spec(Specs.cardManagement())
        .when()
                .delete(Endpoints.BIN, freshBin())
        .then()
                .statusCode(404);
    }
}
