package com.processing.service;

import com.processing.config.SwitchProperties;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingServiceTest {

    private RoutingService routingService;

    @BeforeEach
    void setUp() {
        SwitchProperties properties = new SwitchProperties(
                "1.0.0",
                Map.of("400000", "ISS001", "400001", "ISS002"),
                null, null, null, null, null, null);
        routingService = new RoutingService(properties);
    }

    @ParameterizedTest(name = "pan={0} -> {1}")
    @CsvSource({
            "4000001234567890, ISS001",
            "4000019999999999, ISS002",
            "400000,           ISS001"
    })
    void shouldReturnIssuerIdWhenBinIsKnown(String pan, String expectedIssuerId) {
        // Act
        String issuerId = routingService.getIssuerIdByPan(pan);

        // Assert
        assertThat(issuerId).isEqualTo(expectedIssuerId);
    }

    @ParameterizedTest(name = "pan={0}")
    @ValueSource(strings = {"5000001234567890", "4000091234567890"})
    void shouldThrowUnknownBinWhenBinIsNotInRoutingTable(String pan) {
        // Act + Assert
        assertThatThrownBy(() -> routingService.getIssuerIdByPan(pan))
                .isInstanceOf(UnknownBinException.class)
                .hasMessageContaining(pan.substring(0, 6));
    }

    @ParameterizedTest(name = "pan=''{0}''")
    @NullSource
    @ValueSource(strings = {"", "40000"})
    void shouldThrowUnknownBinWhenPanIsNullOrShorterThanSixDigits(String pan) {
        // Act + Assert
        assertThatThrownBy(() -> routingService.getIssuerIdByPan(pan))
                .isInstanceOf(UnknownBinException.class)
                .hasMessageContaining("null-or-short");
    }
}
