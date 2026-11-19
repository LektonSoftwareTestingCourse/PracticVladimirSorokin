package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.exception.AuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {
    @Mock private AuthorizationClient authorization;
    @Mock private AcquiringFeeClient fees;
    @Mock private LoggerClient logger;
    private RouteService service;

    @BeforeEach
    void setUp() {
        service = new RouteService(new RoutingService(SwitchData.properties()), authorization, fees, logger);
    }

    @Test
    void shouldPublishApprovedPaymentWithIssuerAndFee() {
        // Arrange
        when(authorization.authorize(SwitchData.request())).thenReturn(SwitchData.approved());
        when(fees.fetchAcquiringFee(any(), any(), any(), any(), any())).thenReturn(new BigDecimal("2.50"));
        when(logger.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = service.route(SwitchData.request().withIssuerId("CLIENT-ISSUER"));

        // Assert
        assertThat(response).isEqualTo(SwitchData.approved());
        ArgumentCaptor<TransactionRequest> sent = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(logger).log(sent.capture());
        assertThat(sent.getValue().issuerId()).isEqualTo("ISS001");
        assertThat(sent.getValue().acquiringFee()).isEqualByComparingTo("2.50");
        assertThat(sent.getValue().status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(sent.getValue().rrn()).isEqualTo(response.rrn());
        verify(authorization, never()).rollback(any(), any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"1", "9999991234567890"})
    void shouldDeclineUnknownBinAndPublishDecline(String pan) {
        // Arrange
        AuthorizationRequest request = SwitchData.request();
        request = new AuthorizationRequest(request.mti(), request.stan(), pan, request.processingCode(),
                request.amount(), request.currencyCode(), request.transmissionDateTime(), request.terminalId(),
                request.terminalType(), request.merchantId(), request.mcc(), request.acquirerId(), null);
        when(logger.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = service.route(request);

        // Assert
        assertThat(response.responseCode()).isEqualTo("14");
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        ArgumentCaptor<TransactionRequest> sent = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(logger).log(sent.capture());
        assertThat(sent.getValue().status()).isEqualTo(TransactionStatus.DECLINED);
        verify(authorization, never()).rollback(any(), any());
    }

    @Test
    void shouldReturnTechnicalDeclineWhenAuthorizationIsUnavailable() {
        // Arrange
        when(authorization.authorize(any())).thenThrow(new AuthorizationException("000001", 1, "timeout"));
        when(logger.log(any())).thenReturn(true);

        // Act
        AuthorizationResponse response = service.route(SwitchData.request());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("05");
        assertThat(response.declineReason()).isEqualTo("Authorization service unavailable");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"APPROVED", "DECLINED"})
    void shouldReturn96AfterFailedPublishRegardlessOfRollbackResult(String rollbackStatus) {
        // Arrange
        when(authorization.authorize(any())).thenReturn(SwitchData.approved());
        RollbackResponse rollback = rollbackStatus == null ? null
                : new RollbackResponse("012345678901", "00", rollbackStatus, null, 1);
        when(authorization.rollback(any(), any())).thenReturn(rollback);

        // Act
        AuthorizationResponse response = service.route(SwitchData.request());

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("96");
        verify(authorization).rollback(any(), eq("012345678901"));
    }

    @Test
    void shouldNotRollbackAnAlreadyDeclinedPaymentWhenPublishFails() {
        // Arrange
        AuthorizationResponse declined = new AuthorizationResponse("0110", "000001", null, null,
                "05", "DECLINED", "CARD_BLOCKED", null);
        when(authorization.authorize(any())).thenReturn(declined);

        // Act
        AuthorizationResponse response = service.route(SwitchData.request());

        // Assert
        assertThat(response).isEqualTo(declined);
        verify(authorization, never()).rollback(any(), any());
    }
}
