package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthServiceAuthDeclinedFundsEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedLimitsEvent;
import com.processing.authorization.events.AuthServiceAuthorizeEvent;
import com.processing.authorization.events.AuthServiceRollbackEvent;
import com.processing.authorization.events.AuthorizationEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.GetCardException;
import com.processing.authorization.exceptions.InsufficientFundsException;
import com.processing.authorization.exceptions.InternalCardManagerException;
import com.processing.authorization.exceptions.InvalidGetCardRequestException;
import com.processing.authorization.exceptions.InvalidReserveRequestException;
import com.processing.authorization.exceptions.InvalidRollbackRequestException;
import com.processing.authorization.exceptions.PaymentRequiredException;
import com.processing.authorization.exceptions.ReserveException;
import com.processing.authorization.exceptions.RollbackConflictException;
import com.processing.authorization.exceptions.RollbackFailureException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String PAN = "4000001234567890";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "612345678901";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final BigDecimal BALANCE = new BigDecimal("10000");
    private static final Instant REQUEST_TIME = Instant.now();

    @Mock
    private LimitUsageRepository limitUsageRepository;

    @Mock
    private AuthorizationEventNotifier eventNotifier;

    @Mock
    private BinLookupClient binLookupClient;

    @Mock
    private CardManagementClient cardManagementClient;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                limitUsageRepository,
                eventNotifier,
                binLookupClient,
                cardManagementClient);
    }

    // ---------- authorize: happy path ----------

    @Test
    void shouldApproveAuthorizationWhenCardIsActiveAndSufficientFunds() {
        // Arrange
        givenCard(activeCard());
        givenLimitsUpdated(1);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        assertThat(response.rrn()).isNotBlank();
        assertThat(response.authCode()).hasSize(6);
        verify(eventNotifier).notify(any(AuthServiceAuthorizeEvent.class));
    }

    @Test
    void shouldReserveAmountInCardManagementWhenAuthorizationApproved() {
        // Arrange
        givenCard(activeCard());
        givenLimitsUpdated(1);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        verify(cardManagementClient).reserve(AMOUNT, response.rrn(), PAN);
    }

    @Test
    void shouldApproveWhenAmountEqualsAvailableBalance() {
        // Arrange — граничное значение: сумма ровно равна балансу
        givenCard(cardWithBalance(AMOUNT));
        givenLimitsUpdated(1);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
    }

    // ---------- authorize: проверки карты ----------

    @ParameterizedTest(name = "status={0} -> {1}")
    @CsvSource({
            "EXPIRED,  CARD_EXPIRED,  54",
            "BLOCKED,  CARD_BLOCKED,  05",
            "INACTIVE, CARD_INACTIVE, 05"
    })
    void shouldDeclineWhenCardStatusIsNotActive(CardModelStatus status, String expectedReason, String expectedCode) {
        // Arrange
        givenCard(cardWithStatus(status));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo(expectedReason);
        assertThat(response.responseCode()).isEqualTo(expectedCode);
    }

    @Test
    void shouldDeclineWithUnknownReasonWhenCardStatusIsNull() {
        // Arrange
        givenCard(cardWithStatus(null));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("UNKNOWN_REASON");
    }

    @Test
    void shouldDeclineWhenCardExpiryDateIsInThePast() {
        // Arrange
        givenCard(cardWithExpiry(YearMonth.now().minusMonths(1)));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("CARD_EXPIRED");
    }

    @Test
    void shouldDeclineWhenAmountIsGreaterThanAvailableBalance() {
        // Arrange — граничное значение: баланс на копейку меньше суммы
        givenCard(cardWithBalance(new BigDecimal("999.99")));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(response.responseCode()).isEqualTo("51");
        verify(eventNotifier).notify(any(AuthServiceAuthDeclinedFundsEvent.class));
    }

    // ---------- authorize: ошибки Card Management при getCard ----------

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("getCardErrors")
    void shouldDeclineWhenGetCardFails(RuntimeException error, String expectedReason) {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(error);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo(expectedReason);
        verify(eventNotifier).notify(any(AuthorizationEvent.class));
    }

    static Stream<Arguments> getCardErrors() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("not found"), "CARD_NOT_FOUND"),
                Arguments.of(new InvalidGetCardRequestException("bad pan"), "CARD_NOT_FOUND"),
                Arguments.of(new ServiceUnavailableException("503"), "SERVICE_UNAVAILABLE"),
                Arguments.of(new InternalCardManagerException("500"), "SERVICE_UNAVAILABLE"),
                Arguments.of(new PaymentRequiredException("402"), "CARD_BLOCKED"),
                Arguments.of(new GetCardException("418"), "SERVICE_UNAVAILABLE"),
                Arguments.of(new IllegalStateException("boom"), "UNKNOWN_REASON")
        );
    }

    // ---------- authorize: лимиты ----------

    @Test
    void shouldDeclineWhenDailyOrMonthlyLimitIsExceeded() {
        // Arrange — upsert не обновил ни одной строки = лимит превышен
        givenCard(activeCard());
        givenLimitsUpdated(0);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("EXCEEDS_AMOUNT_LIMIT");
        assertThat(response.responseCode()).isEqualTo("61");
        verify(eventNotifier).notify(any(AuthServiceAuthDeclinedLimitsEvent.class));
    }

    @Test
    void shouldDeclineWhenLimitUsageTableIsUnavailable() {
        // Arrange
        givenCard(activeCard());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
    }

    @Test
    void shouldApproveWhenLimitUpdateSucceedsOnRetryAfterDuplicateKey() {
        // Arrange — первая попытка конфликтует, вторая проходит
        givenCard(activeCard());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("duplicate"))
                .thenReturn(1);

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
    }

    @Test
    void shouldDeclineWhenLimitUpdateFailsOnRetryAfterDuplicateKey() {
        // Arrange
        givenCard(activeCard());
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("duplicate"))
                .thenThrow(new RuntimeException("db down"));

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("DB_UNAVAILABLE");
    }

    // ---------- authorize: ошибки резервирования ----------

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("reserveErrors")
    void shouldDeclineWhenReserveFails(RuntimeException error, String expectedReason) {
        // Arrange
        givenCard(activeCard());
        givenLimitsUpdated(1);
        doThrow(error).when(cardManagementClient).reserve(any(), any(), any());

        // Act
        AuthorizationResponse response = authService.authorize(validRequest(), REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo(expectedReason);
    }

    static Stream<Arguments> reserveErrors() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("not found"), "CARD_NOT_FOUND"),
                Arguments.of(new InvalidReserveRequestException("bad request"), "CARD_NOT_FOUND"),
                Arguments.of(new ServiceUnavailableException("503"), "SERVICE_UNAVAILABLE"),
                Arguments.of(new InternalCardManagerException("500"), "SERVICE_UNAVAILABLE"),
                Arguments.of(new InsufficientFundsException("402"), "INSUFFICIENT_FUNDS"),
                Arguments.of(new ReserveException("418"), "RESERVATION_FAILED"),
                Arguments.of(new IllegalStateException("boom"), "UNKNOWN_REASON")
        );
    }

    // ---------- rollback ----------

    @Test
    void shouldApproveRollbackWhenCardManagementAcceptsIt() {
        // Arrange
        RollbackRequest request = rollbackRequest();

        // Act
        RollbackResponse response = authService.rollback(request, REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.rrn()).isEqualTo(RRN);
        verify(cardManagementClient).rollback(request);
        verify(eventNotifier).notify(any(AuthServiceRollbackEvent.class));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("rollbackErrors")
    void shouldDeclineRollbackWhenCardManagementFails(RuntimeException error, String expectedReason) {
        // Arrange
        RollbackRequest request = rollbackRequest();
        doThrow(error).when(cardManagementClient).rollback(request);

        // Act
        RollbackResponse response = authService.rollback(request, REQUEST_TIME);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo(expectedReason);
    }

    static Stream<Arguments> rollbackErrors() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("not found"), "TRANSACTION_NOT_FOUND"),
                Arguments.of(new InvalidRollbackRequestException("bad request"), "TRANSACTION_NOT_FOUND"),
                Arguments.of(new ServiceUnavailableException("503"), "SERVICE_UNAVAILABLE"),
                Arguments.of(new InternalCardManagerException("500"), "SERVICE_UNAVAILABLE"),
                Arguments.of(new RollbackConflictException("409"), "ALREADY_ROLLED_BACK"),
                Arguments.of(new RollbackFailureException("418"), "ROLLBACK_FAILED"),
                Arguments.of(new IllegalStateException("boom"), "UNKNOWN_REASON")
        );
    }

    // ---------- генерация кодов ----------

    @Test
    void shouldGenerateAuthCodeOfSixUppercaseLettersOrDigits() {
        // Act
        String authCode = authService.generateAuthCode();

        // Assert
        assertThat(authCode).matches("[0-9A-Z]{6}");
    }

    @Test
    void shouldGenerateRrnOfTwelveDigits() {
        // Act
        String rrn = authService.generateRRN();

        // Assert
        assertThat(rrn).matches("\\d{12}");
    }

    @Test
    void shouldGenerateDifferentRrnsForConsecutiveCalls() {
        // Act
        String first = authService.generateRRN();
        String second = authService.generateRRN();

        // Assert
        assertThat(first).isNotEqualTo(second);
    }

    // ---------- фабричные методы (Object Mother) ----------

    private void givenCard(CardModel card) {
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
    }

    private void givenLimitsUpdated(int updatedRows) {
        when(limitUsageRepository.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(updatedRows);
    }

    private AuthorizationRequest validRequest() {
        return new AuthorizationRequest(
                "0100", "000001", PAN, "000000",
                AMOUNT, "643", REQUEST_TIME,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", ISSUER_ID
        );
    }

    private RollbackRequest rollbackRequest() {
        return new RollbackRequest(RRN, PAN, AMOUNT);
    }

    private CardModel activeCard() {
        return card(CardModelStatus.ACTIVE, YearMonth.now().plusYears(2), BALANCE);
    }

    private CardModel cardWithStatus(CardModelStatus status) {
        return card(status, YearMonth.now().plusYears(2), BALANCE);
    }

    private CardModel cardWithExpiry(YearMonth expiryDate) {
        return card(CardModelStatus.ACTIVE, expiryDate, BALANCE);
    }

    private CardModel cardWithBalance(BigDecimal availableBalance) {
        return card(CardModelStatus.ACTIVE, YearMonth.now().plusYears(2), availableBalance);
    }

    private CardModel card(CardModelStatus status, YearMonth expiryDate, BigDecimal availableBalance) {
        return new CardModel(
                UUID.randomUUID(), PAN, BIN, "IVAN IVANOV",
                expiryDate, status, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), availableBalance,
                ISSUER_ID, Instant.now()
        );
    }
}
