package com.processing.exception;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationExceptionTest {

    @ParameterizedTest(name = "lastError={0}")
    @CsvSource(value = {
            "timeout, timeout",
            "NULL,    unknown"
    }, nullValues = "NULL")
    void shouldBuildMessageWithStanAttemptsAndLastError(String lastError, String expectedErrorText) {
        // Act
        AuthorizationException exception = new AuthorizationException("000001", 3, lastError);

        // Assert
        assertThat(exception.getStan()).isEqualTo("000001");
        assertThat(exception.getMessage())
                .contains("STAN=000001")
                .contains("after 3 attempts")
                .endsWith("Last error: " + expectedErrorText);
    }
}
