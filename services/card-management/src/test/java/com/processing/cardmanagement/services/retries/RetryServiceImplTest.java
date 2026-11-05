package com.processing.cardmanagement.services.retries;

import com.processing.cardmanagement.exceptions.OutOfRetriesException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryServiceImplTest {

    private final RetryServiceImpl retryService = new RetryServiceImpl();

    @Test
    void shouldReturnValueWhenFirstAttemptSucceeds() {
        // Act
        String result = retryService.supply(3, () -> "ok");

        // Assert
        assertThat(result).isEqualTo("ok");
    }

    @ParameterizedTest(name = "maxRetries={0}, failures={1}")
    @CsvSource({
            "3, 1",
            "3, 3",   // граничное значение: упали ровно maxRetries раз, последняя попытка успешна
            "1, 1"
    })
    void shouldReturnValueWhenSupplierSucceedsWithinRetries(int maxRetries, int failures) {
        // Arrange
        AtomicInteger calls = new AtomicInteger();

        // Act
        String result = retryService.supply(maxRetries, failingTimes(failures, calls));

        // Assert
        assertThat(result).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(failures + 1);
    }

    @ParameterizedTest(name = "maxRetries={0}")
    @CsvSource({"0", "2"})
    void shouldThrowOutOfRetriesWhenAllAttemptsFail(int maxRetries) {
        // Arrange
        AtomicInteger calls = new AtomicInteger();

        // Act + Assert
        assertThatThrownBy(() -> retryService.supply(maxRetries, failingTimes(Integer.MAX_VALUE, calls)))
                .isInstanceOf(OutOfRetriesException.class);
        assertThat(calls.get()).isEqualTo(maxRetries + 1);
    }

    /** Stub-поставщик: первые {@code failures} вызовов падают, затем возвращает "ok". */
    private Supplier<String> failingTimes(int failures, AtomicInteger calls) {
        return () -> {
            if (calls.incrementAndGet() <= failures) {
                throw new IllegalStateException("collision");
            }
            return "ok";
        };
    }
}
