package com.processing.gateway.logging;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class LogDataMaskerTest {

    private final LogDataMasker masker = new LogDataMasker();

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            // PAN: оставляем первые 6 и последние 4 цифры
            "{\"pan\":\"4000001234567890\"}                 | {\"pan\":\"400000****7890\"}",
            "{\"PAN\" : \"4000001234567890\"}               | {\"pan\":\"400000****7890\"}",
            // CVV: полностью скрываем
            "{\"cvv\":\"123\"}                              | {\"cvv\":\"***\"}",
            "{\"cvv\":\"1234\"}                             | {\"cvv\":\"***\"}",
            // оба поля сразу
            "{\"pan\":\"4000001234567890\",\"cvv\":\"123\"} | {\"pan\":\"400000****7890\",\"cvv\":\"***\"}"
    })
    void shouldMaskSensitiveFields(String input, String expected) {
        // Act
        String masked = masker.maskData(input);

        // Assert
        assertThat(masked).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "{\"stan\":\"000001\"}",
            "{\"pan\":\"40000012\"}",   // слишком короткий PAN — не похож на номер карты
            "plain text without json"
    })
    void shouldKeepDataUnchangedWhenThereIsNothingToMask(String input) {
        // Act
        String masked = masker.maskData(input);

        // Assert
        assertThat(masked).isEqualTo(input);
    }
}
