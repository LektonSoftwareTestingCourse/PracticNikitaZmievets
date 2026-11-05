package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.config.RetryFactory;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HTTP подменяется через {@link MockRestServiceServer} — реальной сети нет, тест остаётся unit-тестом.
 */
class AuthorizationClientTest {

    private static final String AUTH_URL = "http://auth";
    private static final String AUTHORIZE_URL = AUTH_URL + "/api/internal/authorize";
    private static final String ROLLBACK_URL = AUTH_URL + "/api/internal/rollback";
    private static final String HEALTH_URL = AUTH_URL + "/health";
    private static final int MAX_ATTEMPTS = 2;

    private MockRestServiceServer server;
    private CircuitBreaker circuitBreaker;
    private AuthorizationClient client;

    @BeforeEach
    void setUp() {
        SwitchProperties properties = new SwitchProperties(
                "1.0.0", Map.of(), AUTH_URL, null, null, null,
                new SwitchProperties.RetryProperties(MAX_ATTEMPTS, List.of(1L)),
                null);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        circuitBreaker = CircuitBreaker.ofDefaults("test");
        client = new AuthorizationClient(
                properties,
                builder.build(),
                RetryFactory.authorizationRetry(properties),
                circuitBreaker);
    }

    // ---------- authorize ----------

    @Test
    void shouldReturnApprovedResponseWhenAuthorizationApproves() {
        // Arrange
        server.expect(requestTo(AUTHORIZE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(authorizationJson("APPROVED"), MediaType.APPLICATION_JSON));

        // Act
        AuthorizationResponse response = client.authorize(request());

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.rrn()).isEqualTo("612345678901");
        server.verify();
    }

    @Test
    void shouldReturnDeclinedResponseWhenAuthorizationDeclines() {
        // Arrange
        server.expect(requestTo(AUTHORIZE_URL))
                .andRespond(withSuccess(authorizationJson("DECLINED"), MediaType.APPLICATION_JSON));

        // Act
        AuthorizationResponse response = client.authorize(request());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
    }

    @Test
    void shouldRetryAndThrowAuthorizationExceptionWhenResponseStatusIsInvalid() {
        // Arrange — оба ответа с неизвестным статусом, retry делает MAX_ATTEMPTS попыток
        server.expect(ExpectedCount.times(MAX_ATTEMPTS), requestTo(AUTHORIZE_URL))
                .andRespond(withSuccess(authorizationJson("UNKNOWN"), MediaType.APPLICATION_JSON));

        // Act + Assert
        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Invalid response from Authorization");
        server.verify();
    }

    @Test
    void shouldThrowAuthorizationExceptionWhenAuthorizationReturnsServerErrorWithoutBody() {
        // Arrange
        server.expect(ExpectedCount.times(MAX_ATTEMPTS), requestTo(AUTHORIZE_URL))
                .andRespond(withServerError());

        // Act + Assert
        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class);
    }

    @Test
    void shouldThrowAuthorizationExceptionWithoutHttpCallWhenCircuitBreakerIsOpen() {
        // Arrange
        circuitBreaker.transitionToOpenState();

        // Act + Assert
        assertThatThrownBy(() -> client.authorize(request()))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Circuit breaker open");
        server.verify();
    }

    // ---------- rollback ----------

    @Test
    void shouldReturnRollbackResponseWhenAuthorizationAcceptsRollback() {
        // Arrange
        server.expect(requestTo(ROLLBACK_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(rollbackJson("APPROVED"), MediaType.APPLICATION_JSON));

        // Act
        RollbackResponse response = client.rollback(request(), "612345678901");

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
    }

    @Test
    void shouldReturnRollbackResponseWhenAuthorizationDeclinesRollback() {
        // Arrange
        server.expect(requestTo(ROLLBACK_URL))
                .andRespond(withSuccess(rollbackJson("DECLINED"), MediaType.APPLICATION_JSON));

        // Act
        RollbackResponse response = client.rollback(request(), "612345678901");

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
    }

    @Test
    void shouldReturnNullWhenRollbackResponseStatusIsInvalid() {
        // Arrange
        server.expect(requestTo(ROLLBACK_URL))
                .andRespond(withSuccess(rollbackJson("UNKNOWN"), MediaType.APPLICATION_JSON));

        // Act
        RollbackResponse response = client.rollback(request(), "612345678901");

        // Assert
        assertThat(response).isNull();
    }

    @Test
    void shouldReturnNullWhenRollbackFailsWithServerError() {
        // Arrange
        server.expect(requestTo(ROLLBACK_URL)).andRespond(withServerError());

        // Act
        RollbackResponse response = client.rollback(request(), "612345678901");

        // Assert
        assertThat(response).isNull();
    }

    // ---------- health ----------

    @Test
    void shouldReturnOkWhenAuthorizationHealthEndpointResponds() {
        // Arrange
        server.expect(requestTo(HEALTH_URL)).andRespond(withSuccess());

        // Act
        String health = client.checkHealth();

        // Assert
        assertThat(health).isEqualTo("ok");
    }

    @Test
    void shouldReturnDownWhenAuthorizationHealthEndpointFails() {
        // Arrange
        server.expect(requestTo(HEALTH_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        // Act
        String health = client.checkHealth();

        // Assert
        assertThat(health).isEqualTo("down");
    }

    // ---------- фабричные методы ----------

    private AuthorizationRequest request() {
        return new AuthorizationRequest(
                "0100", "000001", "4000001234567890", "000000",
                new BigDecimal("1000"), "643", Instant.parse("2026-01-01T10:00:00Z"),
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ001", "ISS001");
    }

    private String authorizationJson(String status) {
        return """
                {"mti":"0110","stan":"000001","rrn":"612345678901","authCode":"ABC123",
                 "responseCode":"00","status":"%s","declineReason":null,"processingTimeMs":5}
                """.formatted(status);
    }

    private String rollbackJson(String status) {
        return """
                {"rrn":"612345678901","responseCode":"00","status":"%s","declineReason":null,"processingTimeMs":3}
                """.formatted(status);
    }
}
