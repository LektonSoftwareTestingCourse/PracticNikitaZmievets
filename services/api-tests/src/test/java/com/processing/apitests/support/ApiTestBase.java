package com.processing.apitests.support;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;

/**
 * Общий жизненный цикл API-тестов: при падении проверки REST-assured печатает запрос и ответ целиком,
 * чтобы причину было видно в логе CI без повторного прогона.
 */
public abstract class ApiTestBase {

    /** Тег для тестов, которые проверяют контракт там, где реализация расходится с ним (дефекты из отчёта). */
    public static final String KNOWN_DEFECT = "known-defect";

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }
}
