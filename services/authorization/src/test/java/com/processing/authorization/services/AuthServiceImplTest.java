package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.events.AuthServiceAuthorizeEvent;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {
    private static final String PAN = "4000001234567890";
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");
    @Mock private LimitUsageRepository usage;
    @Mock private AuthorizationEventNotifier events;
    @Mock private BinLookupClient bins;
    @Mock private CardManagementClient cards;
    private AuthServiceImpl service;
    private MockedStatic<Instant> time;

    @BeforeEach
    void setUp() {
        time = mockStatic(Instant.class, CALLS_REAL_METHODS);
        time.when(Instant::now).thenReturn(NOW);
        service = new AuthServiceImpl(usage, events, bins, cards);
    }

    @AfterEach
    void closeClock() {
        time.close();
    }

    @Test
    void shouldApproveAndReserveRequestedAmount() {
        // Arrange
        availableCard(CardModelStatus.ACTIVE, YearMonth.of(2027, 1), "1000");
        when(usage.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        AuthorizationResponse response = service.authorize(request("100", NOW), NOW);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        assertThat(response.rrn()).matches("\\d{12}");
        assertThat(response.authCode()).matches("[A-Z0-9]{6}");
        assertThat(response.declineReason()).isNull();
        assertThat(response.processingTimeMs()).isZero();
        verify(cards).reserve(eq(new BigDecimal("100")), eq(response.rrn()), eq(PAN));
        verify(events).notify(isA(AuthServiceAuthorizeEvent.class));
    }

    @ParameterizedTest
    @CsvSource({"BLOCKED,05,CARD_BLOCKED", "INACTIVE,05,CARD_INACTIVE", "EXPIRED,54,CARD_EXPIRED"})
    void shouldDeclineNonActiveCard(CardModelStatus status, String code, String reason) {
        // Arrange
        availableCard(status, YearMonth.of(2027, 1), "1000");

        // Act
        AuthorizationResponse response = service.authorize(request("100", NOW), NOW);

        // Assert
        assertDecline(response, code, reason);
        verify(cards, never()).reserve(any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"99,00,APPROVED", "100,00,APPROVED", "101,51,DECLINED"})
    void shouldCheckBalanceImmediatelyBelowAtAndAboveBoundary(String amount, String code, String status) {
        // Arrange
        availableCard(CardModelStatus.ACTIVE, YearMonth.of(2027, 1), "100");
        org.mockito.Mockito.lenient().when(usage.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        AuthorizationResponse response = service.authorize(request(amount, NOW), NOW);

        // Assert
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.status()).isEqualTo(status);
    }

    @Test
    void shouldDeclineWhenConditionalUsageUpdateRejectsLimit() {
        // Arrange
        availableCard(CardModelStatus.ACTIVE, YearMonth.of(2027, 1), "1000");
        when(usage.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(0);

        // Act
        AuthorizationResponse response = service.authorize(request("100", NOW), NOW);

        // Assert
        assertDecline(response, "61", "EXCEEDS_AMOUNT_LIMIT");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldRetryUsageAfterConcurrentInsertConflict() {
        // Arrange
        availableCard(CardModelStatus.ACTIVE, YearMonth.of(2027, 1), "1000");
        when(usage.upsertLimitUsage(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateKeyException("concurrent insert")).thenReturn(1);

        // Act
        AuthorizationResponse response = service.authorize(request("100", NOW), NOW);

        // Assert
        assertThat(response.responseCode()).isEqualTo("00");
        verify(cards).reserve(any(), eq(response.rrn()), eq(PAN));
    }

    @Test
    void shouldDeclineWhenUsageDatabaseFails() {
        // Arrange
        availableCard(CardModelStatus.ACTIVE, YearMonth.of(2027, 1), "1000");
        when(usage.upsertLimitUsage(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        // Act
        AuthorizationResponse response = service.authorize(request("100", NOW), NOW);

        // Assert
        assertDecline(response, "96", "DB_UNAVAILABLE");
        verify(cards, never()).reserve(any(), any(), any());
    }

    @Test
    void shouldDeclineMissingCard() {
        // Arrange
        when(cards.getCard(PAN)).thenThrow(new CardNotFoundException(PAN));

        // Act
        AuthorizationResponse response = service.authorize(request("100", NOW), NOW);

        // Assert
        assertDecline(response, "14", "CARD_NOT_FOUND");
    }

    @ParameterizedTest
    @CsvSource({"2026-11-30T23:59:59Z,00", "2026-12-01T00:00:00Z,54"})
    void shouldAcceptExpiryMonthUntilItsLastSecond(String transmitted, String code) {
        // Arrange
        availableCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 11), "1000");
        org.mockito.Mockito.lenient().when(usage.upsertLimitUsage(any(), any(), any(), any(), any())).thenReturn(1);

        // Act
        AuthorizationResponse response = service.authorize(request("100", Instant.parse(transmitted)), NOW);

        // Assert
        assertThat(response.responseCode()).isEqualTo(code);
    }

    @ParameterizedTest
    @CsvSource({"2026-11-20T23:59:59Z,2026-11-20", "2026-11-21T00:00:00Z,2026-11-21"})
    void shouldUseTransmissionDayInUtcForUsage(String transmitted, LocalDate expectedDay) {
        // Arrange
        availableCard(CardModelStatus.ACTIVE, YearMonth.of(2027, 1), "1000");
        when(usage.upsertLimitUsage(eq(PAN), eq(expectedDay), any(), any(), any())).thenReturn(1);

        // Act
        AuthorizationResponse response = service.authorize(request("100", Instant.parse(transmitted)), NOW);

        // Assert
        assertThat(response.status()).isEqualTo("APPROVED");
    }

    @Test
    void shouldDelegateRollbackToCardManagement() {
        // Arrange
        RollbackRequest request = new RollbackRequest("012345678901", PAN, new BigDecimal("100"));

        // Act
        RollbackResponse response = service.rollback(request, NOW);

        // Assert
        assertThat(response.responseCode()).isEqualTo("00");
        assertThat(response.status()).isEqualTo("APPROVED");
        verify(cards).rollback(request);
    }

    @Test
    void shouldReportUnavailableCmsDuringRollback() {
        // Arrange
        RollbackRequest request = new RollbackRequest("012345678901", PAN, new BigDecimal("100"));
        doThrow(new ServiceUnavailableException("cms down")).when(cards).rollback(request);

        // Act
        RollbackResponse response = service.rollback(request, NOW);

        // Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("96");
        assertThat(response.declineReason()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    private void availableCard(CardModelStatus status, YearMonth expiry, String balance) {
        when(cards.getCard(PAN)).thenReturn(card(status, expiry, balance));
        when(bins.getIssuerId(PAN)).thenReturn(Optional.empty());
    }

    private CardModel card(CardModelStatus status, YearMonth expiry, String balance) {
        return new CardModel(UUID.fromString("00000000-0000-0000-0000-000000000001"), PAN, "400000",
                "IVAN IVANOV", expiry, status, "643", new BigDecimal("500"), new BigDecimal("2000"),
                new BigDecimal(balance), "ISS001", NOW);
    }

    private AuthorizationRequest request(String amount, Instant transmitted) {
        return new AuthorizationRequest("0100", "000001", PAN, "000000", new BigDecimal(amount), "643", transmitted,
                "TERM0001", "POS", "MERCH0000000001", "5411", "ACQ001", "ISS001");
    }

    private void assertDecline(AuthorizationResponse response, String code, String reason) {
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.rrn()).isNull();
        assertThat(response.authCode()).isNull();
    }
}
