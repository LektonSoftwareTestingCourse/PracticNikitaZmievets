package com.processing.apitests.support;

import org.awaitility.core.ConditionFactory;

import java.time.Duration;

import static org.awaitility.Awaitility.await;

/**
 * Границы ожидания асинхронных эффектов (путь Switch → RabbitMQ → Transaction Logger).
 *
 * <p>Обоснование значений (см. отчёт, раздел 5):</p>
 * <ul>
 *     <li>Задержка «ответ Gateway → запись видна в поиске», измеренная на локальном стеке (10 транзакций):
 *     типично 180–320 мс, худший случай под нагрузкой ~1,8 с.</li>
 *     <li>{@link #DELIVERY_TIMEOUT} = 10 с — ~5x от худшего наблюдённого случая (запас на холодный JVM
 *     и CI-раннер), но достаточно мало, чтобы потеря сообщения проявилась быстро, а не через минуту.</li>
 *     <li>{@link #POLL_INTERVAL} = 250 мс — порядок типичной задержки: чаще опрашивать бессмысленно,
 *     реже — растягивает прогон; не больше 40 запросов к поиску за ожидание.</li>
 *     <li>{@link #ABSENCE_WINDOW} = 3 с — окно наблюдения «эффект не наступил»: больше худшей наблюдённой
 *     задержки (1,8 с) и ~10x типичной, поэтому отсутствие записи за это время — не случайность.</li>
 * </ul>
 */
public final class Waits {

    public static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration POLL_INTERVAL = Duration.ofMillis(250);
    public static final Duration ABSENCE_WINDOW = Duration.ofSeconds(3);

    private Waits() {
    }

    /** Ожидание появления эффекта: проверяем сразу, затем каждые 250 мс, не дольше 10 с. */
    public static ConditionFactory untilDelivered() {
        return await("delivery to transaction journal")
                .pollDelay(Duration.ZERO)
                .pollInterval(POLL_INTERVAL)
                .atMost(DELIVERY_TIMEOUT);
    }

    /**
     * Ожидание отсутствия эффекта: условие должно оставаться истинным на всём окне {@link #ABSENCE_WINDOW}.
     * Это не то же самое, что «проверить один раз и не найти» — разовая проверка прошла бы и при
     * обычной задержке доставки.
     */
    public static ConditionFactory duringAbsenceWindow() {
        return await("effect must stay absent")
                .pollDelay(Duration.ZERO)
                .pollInterval(POLL_INTERVAL)
                .during(ABSENCE_WINDOW)
                .atMost(ABSENCE_WINDOW.plusSeconds(2));
    }
}
