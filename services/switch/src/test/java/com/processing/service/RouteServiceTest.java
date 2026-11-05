package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    private static final String PAN = "4000001234567890";
    private static final String STAN = "000001";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "612345678901";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final BigDecimal FEE = new BigDecimal("15.50");
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-01-01T10:00:00Z");

    @Mock
    private RoutingService routingService;

    @Mock
    private AuthorizationClient authorizationClient;

    @Mock
    private AcquiringFeeClient acquiringFeeClient;

    @Mock
    private LoggerClient loggerClient;

    private RouteService routeService;

    @BeforeEach
    void setUp() {
        routeService = new RouteService(routingService, authorizationClient, acquiringFeeClient, loggerClient);
    }

    // ---------- успешная маршрутизация ----------

    @Test
    void shouldReturnApprovedResponseWhenAuthorizationApprovesAndTransactionIsLogged() {
        // Arrange
        givenIssuerFound();
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        givenLogResult(true);

        // Act
        AuthorizationResponse response = routeService.route(request(PAN));

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.rrn()).isEqualTo(RRN);
        verify(authorizationClient, never()).rollback(any(), anyString());
    }

    @Test
    void shouldSendNormalizedRequestWithIssuerIdToAuthorization() {
        // Arrange
        givenIssuerFound();
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        givenLogResult(true);
        ArgumentCaptor<AuthorizationRequest> captor = ArgumentCaptor.forClass(AuthorizationRequest.class);

        // Act
        routeService.route(request(PAN));

        // Assert
        verify(authorizationClient).authorize(captor.capture());
        assertThat(captor.getValue().issuerId()).isEqualTo(ISSUER_ID);
        assertThat(captor.getValue().terminalId()).isEqualTo("TERM0000");
        assertThat(captor.getValue().merchantId()).isEqualTo("MERCH2000000000");
    }

    @Test
    void shouldPublishTransactionWithIssuerFeeAndStatusToLogger() {
        // Arrange
        givenIssuerFound();
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(acquiringFeeClient.fetchAcquiringFee(any(), any(), any(), any(), any())).thenReturn(FEE);
        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        when(loggerClient.log(captor.capture())).thenReturn(true);

        // Act
        routeService.route(request(PAN));

        // Assert
        TransactionRequest transaction = captor.getValue();
        assertThat(transaction.issuerId()).isEqualTo(ISSUER_ID);
        assertThat(transaction.acquiringFee()).isEqualTo(FEE);
        assertThat(transaction.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(transaction.rrn()).isEqualTo(RRN);
        assertThat(transaction.processingTimeMs()).isEqualTo(12);
    }

    @Test
    void shouldLogZeroProcessingTimeWhenAuthorizationDidNotReturnIt() {
        // Arrange
        givenIssuerFound();
        AuthorizationResponse withoutTime = new AuthorizationResponse(
                "0110", STAN, RRN, "ABC123", "00", "APPROVED", null, null);
        when(authorizationClient.authorize(any())).thenReturn(withoutTime);
        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        when(loggerClient.log(captor.capture())).thenReturn(true);

        // Act
        routeService.route(request(PAN));

        // Assert
        assertThat(captor.getValue().processingTimeMs()).isZero();
    }

    @Test
    void shouldReturnDeclinedResponseFromAuthorizationAsIs() {
        // Arrange
        givenIssuerFound();
        when(authorizationClient.authorize(any())).thenReturn(declinedResponse());
        givenLogResult(true);

        // Act
        AuthorizationResponse response = routeService.route(request(PAN));

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    // ---------- ошибки маршрутизации и авторизации ----------

    @ParameterizedTest(name = "pan=''{0}''")
    @NullSource
    @ValueSource(strings = {"40000", PAN})
    void shouldDeclineWithCardNotFoundWhenBinIsUnknown(String pan) {
        // Arrange
        when(routingService.getIssuerIdByPan(pan)).thenThrow(new UnknownBinException("unknown"));
        givenLogResult(true);

        // Act
        AuthorizationResponse response = routeService.route(request(pan));

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("14");
        verify(authorizationClient, never()).authorize(any());
    }

    @Test
    void shouldDeclineWhenAuthorizationServiceIsUnavailable() {
        // Arrange
        givenIssuerFound();
        when(authorizationClient.authorize(any())).thenThrow(new AuthorizationException(STAN, 3, "timeout"));
        givenLogResult(true);

        // Act
        AuthorizationResponse response = routeService.route(request(PAN));

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("05");
        assertThat(response.declineReason()).isEqualTo("Authorization service unavailable");
    }

    // ---------- сбой публикации в очередь ----------

    @ParameterizedTest(name = "rollback={0}")
    @MethodSource("rollbackResults")
    void shouldRollbackAndReturnSystemErrorWhenApprovedTransactionWasNotLogged(RollbackResponse rollbackResult) {
        // Arrange
        givenIssuerFound();
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        givenLogResult(false);
        when(authorizationClient.rollback(any(), anyString())).thenReturn(rollbackResult);

        // Act
        AuthorizationResponse response = routeService.route(request(PAN));

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("96");
        verify(authorizationClient).rollback(any(AuthorizationRequest.class), anyString());
    }

    static Stream<Arguments> rollbackResults() {
        return Stream.of(
                Arguments.of(new RollbackResponse(RRN, "00", "APPROVED", null, 5)),
                Arguments.of(new RollbackResponse(RRN, "14", "DECLINED", "TRANSACTION_NOT_FOUND", 5)),
                Arguments.of((RollbackResponse) null)
        );
    }

    @Test
    void shouldNotRollbackWhenDeclinedTransactionWasNotLogged() {
        // Arrange
        givenIssuerFound();
        when(authorizationClient.authorize(any())).thenReturn(declinedResponse());
        givenLogResult(false);

        // Act
        AuthorizationResponse response = routeService.route(request(PAN));

        // Assert
        assertThat(response.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        verify(authorizationClient, never()).rollback(any(), anyString());
    }

    // ---------- фабричные методы ----------

    private void givenIssuerFound() {
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
    }

    private void givenLogResult(boolean published) {
        when(loggerClient.log(any())).thenReturn(published);
    }

    private AuthorizationRequest request(String pan) {
        return new AuthorizationRequest(
                "0100", STAN, pan, "000000",
                AMOUNT, "643", TRANSMISSION_TIME,
                "TERM", "POS", "MERCH2", "5045", "ACQ001", null);
    }

    private AuthorizationResponse approvedResponse() {
        return new AuthorizationResponse("0110", STAN, RRN, "ABC123", "00", "APPROVED", null, 12);
    }

    private AuthorizationResponse declinedResponse() {
        return new AuthorizationResponse("0110", STAN, null, null, "51", "DECLINED", "INSUFFICIENT_FUNDS", 7);
    }
}
