package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestNormalizerTest {

    // ---------- terminalId: всегда 8 символов ----------

    @ParameterizedTest(name = "''{0}'' -> ''{1}''")
    @CsvSource({
            "TERM0001,   TERM0001",   // ровно 8 — без изменений
            "TERM00012,  TERM0001",   // 9 — обрезка
            "TERM,       TERM0000",   // 4 — дополнение нулями
            "T,          T0000000"    // 1 — дополнение нулями
    })
    void shouldNormalizeTerminalIdToEightCharacters(String input, String expected) {
        // Act
        String result = AuthorizationRequestNormalizer.normalizeTerminalId(input);

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest(name = "''{0}''")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldKeepTerminalIdAsIsWhenItIsNullOrBlank(String input) {
        // Act
        String result = AuthorizationRequestNormalizer.normalizeTerminalId(input);

        // Assert
        assertThat(result).isEqualTo(input);
    }

    // ---------- merchantId: всегда 15 символов ----------

    @ParameterizedTest(name = "''{0}'' -> ''{1}''")
    @CsvSource({
            "MERCH0000000002,    MERCH0000000002",  // ровно 15 — без изменений
            "MERCH00000000002,   MERCH0000000002",  // 16 и лишний 0 после MERCH — удаляем этот 0
            "MERCH10000000002,   MERCH1000000000",  // 16, но после MERCH не 0 — обрезка
            "SHOP000000000002,   SHOP00000000000",  // 16 без префикса MERCH — обрезка
            "MERCH00000000000123, MERCH0000000000",  // 19 — обрезка
            "MERCH2,             MERCH2000000000"   // 6 — дополнение нулями
    })
    void shouldNormalizeMerchantIdToFifteenCharacters(String input, String expected) {
        // Act
        String result = AuthorizationRequestNormalizer.normalizeMerchantId(input);

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest(name = "''{0}''")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void shouldKeepMerchantIdAsIsWhenItIsNullOrBlank(String input) {
        // Act
        String result = AuthorizationRequestNormalizer.normalizeMerchantId(input);

        // Assert
        assertThat(result).isEqualTo(input);
    }

    // ---------- normalize: весь запрос ----------

    @Test
    void shouldNormalizeOnlyTerminalAndMerchantIdsAndKeepOtherFields() {
        // Arrange
        AuthorizationRequest request = new AuthorizationRequest(
                "0100", "000001", "4000001234567890", "000000",
                new BigDecimal("100"), "643", Instant.parse("2026-01-01T10:00:00Z"),
                "TERM", "POS", "MERCH2", "5045", "ACQ001", null);

        // Act
        AuthorizationRequest result = AuthorizationRequestNormalizer.normalize(request);

        // Assert
        assertThat(result.terminalId()).isEqualTo("TERM0000");
        assertThat(result.merchantId()).isEqualTo("MERCH2000000000");
        assertThat(result.pan()).isEqualTo(request.pan());
        assertThat(result.amount()).isEqualTo(request.amount());
        assertThat(result.stan()).isEqualTo(request.stan());
    }
}
