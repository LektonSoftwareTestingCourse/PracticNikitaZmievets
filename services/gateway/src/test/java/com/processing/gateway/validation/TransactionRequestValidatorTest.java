package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRequestValidatorTest {

    private final TransactionRequestValidator validator = new TransactionRequestValidator();

    @Test
    void shouldAcceptValidRequest() {
        // Act + Assert
        assertThatCode(() -> validator.validate(validRequest().build()))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectNullRequestBody() {
        // Act + Assert
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Request body is required");
    }

    // ---------- обязательные поля ----------

    @ParameterizedTest(name = "{0} отсутствует")
    @MethodSource("requestsWithMissingField")
    void shouldRejectRequestWhenRequiredFieldIsMissing(String fieldName, AuthorizationRequest request) {
        // Act + Assert
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field '" + fieldName + "' is required");
    }

    static Stream<Arguments> requestsWithMissingField() {
        return Stream.of(
                Arguments.of("mti", validRequest().mti(null).build()),
                Arguments.of("stan", validRequest().stan("  ").build()),
                Arguments.of("pan", validRequest().pan("").build()),
                Arguments.of("processingCode", validRequest().processingCode(null).build()),
                Arguments.of("currencyCode", validRequest().currencyCode(null).build()),
                Arguments.of("transmissionDateTime", validRequest().transmissionDateTime(null).build()),
                Arguments.of("terminalId", validRequest().terminalId(null).build()),
                Arguments.of("merchantId", validRequest().merchantId(null).build()),
                Arguments.of("mcc", validRequest().mcc(null).build()),
                Arguments.of("acquirerId", validRequest().acquirerId(null).build()),
                Arguments.of("amount", validRequest().amount(null).build())
        );
    }

    // ---------- форматы полей ----------

    @ParameterizedTest(name = "mti={0}")
    @ValueSource(strings = {"0200", "0400", "100"})
    void shouldRejectRequestWhenMtiIsNot0100(String mti) {
        // Act + Assert
        assertThatThrownBy(() -> validator.validate(validRequest().mti(mti).build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'mti' must be '0100'");
    }

    @ParameterizedTest(name = "pan={0}")
    @ValueSource(strings = {
            "400000123456789",     // 15 цифр
            "40000012345678901",   // 17 цифр
            "400000123456789A"     // буква
    })
    void shouldRejectRequestWhenPanIsNotSixteenDigits(String pan) {
        // Act + Assert
        assertThatThrownBy(() -> validator.validate(validRequest().pan(pan).build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'pan' must be exactly 16 digits");
    }

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"0", "-0.01", "-100"})
    void shouldRejectRequestWhenAmountIsNotPositive(String amount) {
        // Act + Assert
        assertThatThrownBy(() -> validator.validate(validRequest().amount(new BigDecimal(amount)).build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'amount' must be > 0");
    }

    @Test
    void shouldAcceptSmallestPositiveAmount() {
        // Act + Assert — граничное значение: минимальная положительная сумма
        assertThatCode(() -> validator.validate(validRequest().amount(new BigDecimal("0.01")).build()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "currencyCode={0}")
    @ValueSource(strings = {"64", "6430"})
    void shouldRejectRequestWhenCurrencyCodeIsNotThreeCharacters(String currencyCode) {
        // Act + Assert
        assertThatThrownBy(() -> validator.validate(validRequest().currencyCode(currencyCode).build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'currencyCode' must be exactly 3 characters");
    }

    @ParameterizedTest(name = "mcc={0}")
    @CsvSource({"504", "50450", "50A5"})
    void shouldRejectRequestWhenMccIsNotFourDigits(String mcc) {
        // Act + Assert
        assertThatThrownBy(() -> validator.validate(validRequest().mcc(mcc).build()))
                .isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'mcc' must be exactly 4 digits");
    }

    @ParameterizedTest(name = "terminalType=''{0}''")
    @NullAndEmptySource
    void shouldAcceptRequestWithoutOptionalTerminalType(String terminalType) {
        // Act + Assert
        assertThatCode(() -> validator.validate(validRequest().terminalType(terminalType).build()))
                .doesNotThrowAnyException();
    }

    // ---------- фабричный метод ----------

    private static AuthorizationRequest.AuthorizationRequestBuilder validRequest() {
        return AuthorizationRequest.builder()
                .mti("0100")
                .stan("000001")
                .pan("4000001234567890")
                .processingCode("000000")
                .amount(new BigDecimal("1000"))
                .currencyCode("643")
                .transmissionDateTime(Instant.parse("2026-01-01T10:00:00Z"))
                .terminalId("TERM0001")
                .terminalType("POS")
                .merchantId("MERCH0000000002")
                .mcc("5045")
                .acquirerId("ACQ001");
    }
}
