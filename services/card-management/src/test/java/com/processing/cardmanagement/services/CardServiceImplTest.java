package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardServiceCreationEvent;
import com.processing.cardmanagement.events.CardServicePatchEvent;
import com.processing.cardmanagement.events.CardsAmountUpdatedEvent;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {

    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "612345678901";
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("100000");
    private static final BigDecimal MONTHLY_LIMIT = new BigDecimal("500000");
    private static final BigDecimal BALANCE = new BigDecimal("10000");
    private static final int MAX_PAGE_LIMIT = 100;
    private static final int DEFAULT_PAGE_LIMIT = 20;
    private static final long DEFAULT_PAGE_OFFSET = 0L;

    @Mock
    private CardRepository cardRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationRollbackRepository reservationRollbackRepository;

    @Mock
    private CardServiceDefaults defaults;

    @Mock
    private PanGenerator panGenerator;

    @Mock
    private CardEventNotifier eventNotifier;

    @Mock
    private BinIssuerService binIssuerService;

    private CardServiceImpl cardService;

    @BeforeEach
    void setUp() {
        cardService = new CardServiceImpl(
                cardRepository,
                reservationRepository,
                reservationRollbackRepository,
                new FixedSettings(),
                defaults,
                panGenerator,
                eventNotifier,
                binIssuerService,
                new RetryServiceImpl(),
                new DirectTransactionRunner());
    }

    // ---------- createCard ----------

    @Test
    void shouldCreateActiveCardWithGeneratedPanAndIssuerFromBin() {
        // Arrange
        when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
        when(panGenerator.generatePan(BIN)).thenReturn(PAN);
        givenRepositoryCreateReturnsSavedCard();

        // Act
        Card card = cardService.createCard(BIN, "IVAN IVANOV", "643", DAILY_LIMIT, MONTHLY_LIMIT, BALANCE);

        // Assert
        assertThat(card.pan()).isEqualTo(PAN);
        assertThat(card.issuerId()).isEqualTo(ISSUER_ID);
        assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(card.expiryDate()).isEqualTo(YearMonth.now().plusYears(3));
        verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(1));
    }

    // ---------- getCard ----------

    @Test
    void shouldReturnCardWhenPanExists() {
        // Arrange
        Card card = cardWithStatus(CardStatus.ACTIVE);
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.of(card));

        // Act
        Card result = cardService.getCard(PAN);

        // Assert
        assertThat(result).isEqualTo(card);
    }

    @Test
    void shouldThrowCardNotFoundWhenPanDoesNotExist() {
        // Arrange
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> cardService.getCard(PAN))
                .isInstanceOf(CardNotFoundException.class);
    }

    // ---------- getCards: пагинация ----------

    @Test
    void shouldUseDefaultLimitAndOffsetWhenTheyAreNotProvided() {
        // Arrange
        List<Card> cards = List.of(cardWithStatus(CardStatus.ACTIVE));
        when(defaults.pageLimit()).thenReturn(DEFAULT_PAGE_LIMIT);
        when(defaults.pageOffset()).thenReturn(DEFAULT_PAGE_OFFSET);
        when(cardRepository.findCards(DEFAULT_PAGE_LIMIT, DEFAULT_PAGE_OFFSET, null, null, null, null, null))
                .thenReturn(cards);

        // Act
        List<Card> result = cardService.getCards(null, null, null, null, null, null, null);

        // Assert
        assertThat(result).isEqualTo(cards);
    }

    @Test
    void shouldAllowLimitEqualToMaxPageLimit() {
        // Arrange — граничное значение: ровно максимум
        List<Card> cards = List.of(cardWithStatus(CardStatus.ACTIVE));
        when(cardRepository.findCards(MAX_PAGE_LIMIT, 5L, CardStatus.ACTIVE, BIN, null, null, null))
                .thenReturn(cards);

        // Act
        List<Card> result = cardService.getCards(MAX_PAGE_LIMIT, 5L, CardStatus.ACTIVE, BIN, null, null, null);

        // Assert
        assertThat(result).isEqualTo(cards);
    }

    @Test
    void shouldThrowTooLargeLimitWhenLimitIsGreaterThanMaxPageLimit() {
        // Act + Assert — граничное значение: максимум + 1
        assertThatThrownBy(() -> cardService.getCards(MAX_PAGE_LIMIT + 1, null, null, null, null, null, null))
                .isInstanceOf(TooLargeLimitException.class);
    }

    // ---------- patchCard ----------

    @Test
    void shouldKeepCurrentValuesWhenPatchFieldsAreNull() {
        // Arrange
        Card card = cardWithStatus(CardStatus.ACTIVE);
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
        givenRepositoryUpdateReturnsUpdatedCard();

        // Act
        Card result = cardService.patchCard(PAN, null, null, null, null);

        // Assert
        assertThat(result.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(result.dailyLimit()).isEqualTo(DAILY_LIMIT);
        assertThat(result.monthlyLimit()).isEqualTo(MONTHLY_LIMIT);
        assertThat(result.availableBalance()).isEqualTo(BALANCE);
        verify(eventNotifier).notifyListeners(new CardServicePatchEvent(PAN));
    }

    @Test
    void shouldApplyNewValuesWhenPatchFieldsAreProvided() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(cardWithStatus(CardStatus.ACTIVE)));
        givenRepositoryUpdateReturnsUpdatedCard();

        // Act
        Card result = cardService.patchCard(
                PAN, CardStatus.BLOCKED, new BigDecimal("1000"), new BigDecimal("2000"), new BigDecimal("50"));

        // Assert
        assertThat(result.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(result.dailyLimit()).isEqualByComparingTo("1000");
        assertThat(result.monthlyLimit()).isEqualByComparingTo("2000");
        assertThat(result.availableBalance()).isEqualByComparingTo("50");
    }

    @Test
    void shouldThrowCardNotFoundWhenPatchingUnknownCard() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

        // Act + Assert
        assertThatThrownBy(() -> cardService.patchCard(PAN, CardStatus.BLOCKED, null, null, null))
                .isInstanceOf(CardNotFoundException.class);
    }

    // ---------- deleteCard ----------

    @Test
    void shouldSaveCardWithDeletedStatusWhenDeleting() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(cardWithStatus(CardStatus.ACTIVE)));

        // Act
        cardService.deleteCard(PAN);

        // Assert
        verify(cardRepository).update(argThat(card -> card.status() == CardStatus.DELETED));
    }

    // ---------- reserve ----------

    @Test
    void shouldDecreaseAvailableBalanceWhenReservationSucceeds() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(cardWithStatus(CardStatus.ACTIVE)));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(true);
        when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        givenRepositoryUpdateReturnsUpdatedCard();

        // Act
        Card result = cardService.reserve(PAN, new BigDecimal("1000"), RRN);

        // Assert
        assertThat(result.availableBalance()).isEqualByComparingTo("9000");
    }

    @Test
    void shouldThrowReservationAlreadyExistsWhenRrnWasAlreadyUsedForThisPan() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(cardWithStatus(CardStatus.ACTIVE)));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(false);

        // Act + Assert
        assertThatThrownBy(() -> cardService.reserve(PAN, new BigDecimal("1000"), RRN))
                .isInstanceOf(ReservationAlreadyExistsException.class);
        verify(reservationRepository, never()).save(any());
    }

    @Test
    void shouldThrowInsufficientFundsWhenAmountIsGreaterThanBalance() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(cardWithStatus(CardStatus.ACTIVE)));

        // Act + Assert
        assertThatThrownBy(() -> cardService.reserve(PAN, new BigDecimal("10000.01"), RRN))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"ACTIVE"})
    void shouldRejectReservationWhenCardIsNotActive(CardStatus status) {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(cardWithStatus(status)));

        // Act + Assert
        assertThatThrownBy(() -> cardService.reserve(PAN, new BigDecimal("1"), RRN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(status.name());
    }

    // ---------- bulkUpdateStatus ----------

    @ParameterizedTest(name = "bins={0}, pans={1}")
    @MethodSource("invalidBulkArguments")
    void shouldRejectBulkUpdateWhenNotExactlyOneOfBinsOrPansProvided(List<String> bins, List<String> pans) {
        // Act + Assert
        assertThatThrownBy(() -> cardService.bulkUpdateStatus(bins, pans, CardStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidBulkArguments() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of(List.of(BIN), List.of(PAN))
        );
    }

    @Test
    void shouldUpdateStatusOfAllCardsFoundByBins() {
        // Arrange
        List<Card> cards = List.of(cardWithStatus(CardStatus.ACTIVE), cardWithStatus(CardStatus.INACTIVE));
        when(cardRepository.findCardsByBinsForUpdate(List.of(BIN))).thenReturn(cards);

        // Act
        int updated = cardService.bulkUpdateStatus(List.of(BIN), null, CardStatus.BLOCKED);

        // Assert
        assertThat(updated).isEqualTo(2);
        verify(cardRepository, times(2))
                .update(argThat(card -> card.status() == CardStatus.BLOCKED));
    }

    @Test
    void shouldUpdateStatusOfAllCardsFoundByPans() {
        // Arrange
        when(cardRepository.findCardsByPansForUpdate(List.of(PAN))).thenReturn(List.of(cardWithStatus(CardStatus.ACTIVE)));

        // Act
        int updated = cardService.bulkUpdateStatus(null, List.of(PAN), CardStatus.EXPIRED);

        // Assert
        assertThat(updated).isEqualTo(1);
        verify(cardRepository).update(argThat(card -> card.status() == CardStatus.EXPIRED));
    }

    // ---------- countAllCardsAndUpdate ----------

    @Test
    void shouldReturnCardsCountAndNotifyListeners() {
        // Arrange
        when(cardRepository.countAllCards()).thenReturn(42L);

        // Act
        long count = cardService.countAllCardsAndUpdate();

        // Assert
        assertThat(count).isEqualTo(42L);
        verify(eventNotifier).notifyListeners(new CardsAmountUpdatedEvent(42L));
    }

    // ---------- фабричные методы и test doubles ----------

    private void givenRepositoryCreateReturnsSavedCard() {
        when(cardRepository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void givenRepositoryUpdateReturnsUpdatedCard() {
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private Card cardWithStatus(CardStatus status) {
        return new Card(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                YearMonth.now().plusYears(2), status, "643",
                DAILY_LIMIT, MONTHLY_LIMIT, BALANCE,
                ISSUER_ID, Instant.now());
    }

    /** Fake: настройки сервиса с фиксированными значениями. */
    private static final class FixedSettings implements CardServiceSettings {
        @Override
        public int cardValidityPeriod() {
            return 3;
        }

        @Override
        public int maxPageLimit() {
            return MAX_PAGE_LIMIT;
        }

        @Override
        public int maxCardCreationRetries() {
            return 2;
        }
    }

    /** Fake: выполняет операцию сразу, без настоящей транзакции БД. */
    private static final class DirectTransactionRunner implements TransactionRunner {
        @Override
        public void run(Runnable operation) {
            operation.run();
        }

        @Override
        public <T> T runSupplier(Supplier<T> operation) {
            return operation.get();
        }

        @Override
        public <T> T runSupplierWithNestedQuery(Function<TransactionRunner, T> supplier) {
            return supplier.apply(this);
        }
    }
}
