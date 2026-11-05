package com.processing.cardmanagement.services;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LuhnValidatorTest {

    private final LuhnValidator validator = new LuhnValidator();

    @ParameterizedTest(name = "pan={0}")
    @ValueSource(strings = {
            "4111111111111111",   // классический тестовый номер Visa
            "5555555555554444",   // тестовый номер MasterCard
            "4000000000000002",
            "0000000000000000"
    })
    void shouldAcceptPanWhenLuhnChecksumIsCorrect(String pan) {
        // Act
        boolean valid = validator.isValid(pan);

        // Assert
        assertThat(valid).isTrue();
    }

    @ParameterizedTest(name = "pan={0}")
    @ValueSource(strings = {
            "4111111111111112",   // изменена контрольная цифра
            "5555555555554445",
            "4000000000000001"
    })
    void shouldRejectPanWhenLuhnChecksumIsWrong(String pan) {
        // Act
        boolean valid = validator.isValid(pan);

        // Assert
        assertThat(valid).isFalse();
    }

    @RepeatedTest(5)
    void shouldGenerateSixteenDigitPanThatStartsWithBinAndPassesLuhn() {
        // Act
        String pan = validator.generatePan("400000");

        // Assert
        assertThat(pan).hasSize(16).startsWith("400000").containsOnlyDigits();
        assertThat(validator.isValid(pan)).isTrue();
    }
}
