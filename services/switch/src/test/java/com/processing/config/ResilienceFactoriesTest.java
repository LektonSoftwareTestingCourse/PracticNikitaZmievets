package com.processing.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ResilienceFactoriesTest {

    // ---------- RetryFactory ----------

    @ParameterizedTest(name = "backoffMs={0}")
    @MethodSource("backoffLists")
    void shouldCreateRetryWithConfiguredMaxAttemptsForAnyBackoffList(List<Long> backoffMs) {
        // Arrange
        SwitchProperties properties = propertiesWithRetry(3, backoffMs);

        // Act
        Retry retry = RetryFactory.loggerRetry(properties);

        // Assert
        assertThat(retry.getName()).isEqualTo("logger");
        assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(3);
    }

    static Stream<Arguments> backoffLists() {
        return Stream.of(
                Arguments.of((List<Long>) null),
                Arguments.of(List.of()),
                Arguments.of(List.of(1000L, 2000L, 4000L))
        );
    }

    @Test
    void shouldCreateAuthorizationRetryWithName() {
        // Act
        Retry retry = RetryFactory.authorizationRetry(propertiesWithRetry(2, List.of(10L)));

        // Assert
        assertThat(retry.getName()).isEqualTo("authorization");
    }

    // ---------- CircuitBreakerFactory ----------

    @Test
    void shouldCreateAuthorizationCircuitBreakerFromProperties() {
        // Arrange
        SwitchProperties properties = new SwitchProperties(
                "1.0.0", Map.of(), null, null, null, null, null,
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 10, 5, 30_000)));

        // Act
        CircuitBreaker circuitBreaker = CircuitBreakerFactory.authorizationCircuitBreaker(properties);

        // Assert
        assertThat(circuitBreaker.getName()).isEqualTo("authorization");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreaker.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50f);
        assertThat(circuitBreaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(10);
        assertThat(circuitBreaker.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(5);
    }

    private SwitchProperties propertiesWithRetry(int maxAttempts, List<Long> backoffMs) {
        return new SwitchProperties(
                "1.0.0", Map.of(), null, null, null, null,
                new SwitchProperties.RetryProperties(maxAttempts, backoffMs),
                null);
    }
}
