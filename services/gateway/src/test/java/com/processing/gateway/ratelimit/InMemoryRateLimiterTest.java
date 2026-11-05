package com.processing.gateway.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Время подменяется fake-тикером — тесты не зависят от реальных часов и не используют sleep.
 */
class InMemoryRateLimiterTest {

    private static final String CLIENT = "10.0.0.1";
    private static final long ONE_SECOND_NANOS = 1_000_000_000L;

    private final AtomicLong nowNanos = new AtomicLong();
    private InMemoryRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        // capacity = 2 запроса, пополнение 1 токен в секунду
        rateLimiter = InMemoryRateLimiter.forTesting(2, 1, Duration.ofMinutes(10), 100, nowNanos::get);
    }

    @Test
    void shouldAllowRequestsUpToCapacity() {
        // Act
        boolean first = rateLimiter.allowRequest(CLIENT);
        boolean second = rateLimiter.allowRequest(CLIENT);

        // Assert
        assertThat(first).isTrue();
        assertThat(second).isTrue();
    }

    @Test
    void shouldRejectRequestWhenCapacityIsExhausted() {
        // Arrange
        rateLimiter.allowRequest(CLIENT);
        rateLimiter.allowRequest(CLIENT);

        // Act
        boolean third = rateLimiter.allowRequest(CLIENT);

        // Assert
        assertThat(third).isFalse();
    }

    @Test
    void shouldAllowRequestAgainAfterTokensAreRefilled() {
        // Arrange
        rateLimiter.allowRequest(CLIENT);
        rateLimiter.allowRequest(CLIENT);
        nowNanos.addAndGet(ONE_SECOND_NANOS);

        // Act
        boolean afterRefill = rateLimiter.allowRequest(CLIENT);

        // Assert
        assertThat(afterRefill).isTrue();
    }

    @Test
    void shouldNotRefillMoreThanCapacityAfterLongPause() {
        // Arrange — после паузы в 100 секунд в корзине всё равно максимум 2 токена
        rateLimiter.allowRequest(CLIENT);
        nowNanos.addAndGet(100 * ONE_SECOND_NANOS);
        rateLimiter.allowRequest(CLIENT);
        rateLimiter.allowRequest(CLIENT);

        // Act
        boolean third = rateLimiter.allowRequest(CLIENT);

        // Assert
        assertThat(third).isFalse();
    }

    @Test
    void shouldKeepSeparateLimitsForDifferentClients() {
        // Arrange
        rateLimiter.allowRequest(CLIENT);
        rateLimiter.allowRequest(CLIENT);

        // Act
        boolean otherClient = rateLimiter.allowRequest("10.0.0.2");

        // Assert
        assertThat(otherClient).isTrue();
    }

    @Test
    void shouldRejectAllRequestsWhenCapacityIsZero() {
        // Arrange
        InMemoryRateLimiter closed = InMemoryRateLimiter.forTesting(0, 1, Duration.ofMinutes(10), 100, nowNanos::get);

        // Act
        boolean allowed = closed.allowRequest(CLIENT);

        // Assert
        assertThat(allowed).isFalse();
    }
}
