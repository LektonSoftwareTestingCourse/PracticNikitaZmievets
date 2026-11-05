package com.processing.service;

import com.processing.config.SwitchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MerchantAcquirerClientTest {

    private static final String FEE_URL = "http://merchant/api/simulator/merchant/fee";

    private MockRestServiceServer server;
    private MerchantAcquirerClient client;

    @BeforeEach
    void setUp() {
        SwitchProperties properties = new SwitchProperties(
                "1.0.0", Map.of(), null, null, "http://merchant", null, null, null);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MerchantAcquirerClient(properties, builder.build());
    }

    @Test
    void shouldReturnFeeWhenMerchantAcquirerResponds() {
        // Arrange
        server.expect(requestTo(FEE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"acquirerFee\":15.50}", MediaType.APPLICATION_JSON));

        // Act
        BigDecimal fee = fetchFee();

        // Assert
        assertThat(fee).isEqualByComparingTo("15.50");
    }

    @Test
    void shouldReturnNullWhenMerchantAcquirerReturnsEmptyBody() {
        // Arrange
        server.expect(requestTo(FEE_URL)).andRespond(withSuccess());

        // Act
        BigDecimal fee = fetchFee();

        // Assert
        assertThat(fee).isNull();
    }

    @Test
    void shouldReturnNullWhenMerchantAcquirerIsUnavailable() {
        // Arrange
        server.expect(requestTo(FEE_URL)).andRespond(withServerError());

        // Act
        BigDecimal fee = fetchFee();

        // Assert
        assertThat(fee).isNull();
    }

    private BigDecimal fetchFee() {
        return client.fetchAcquiringFee(
                Instant.parse("2026-01-01T10:00:00Z"), "000001", "4000001234567890",
                "TERM0001", new BigDecimal("1000"));
    }
}
