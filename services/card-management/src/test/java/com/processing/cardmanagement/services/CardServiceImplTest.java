package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardServiceReserveEvent;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {
    private static final String PAN = "4000001234567890";
    @Mock private CardRepository cards;
    @Mock private ReservationRepository reservations;
    @Mock private CardServiceSettings settings;
    @Mock private CardServiceDefaults defaults;
    @Mock private CardEventNotifier events;
    @Mock private TransactionRunner transactions;
    private CardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CardServiceImpl(cards, reservations, mock(ReservationRollbackRepository.class), settings,
                defaults, mock(PanGenerator.class), events, mock(BinIssuerService.class),
                mock(RetryService.class), transactions);
    }

    @Test
    void shouldReturnFoundCard() {
        // Arrange
        Card expected = card();
        when(cards.findByPan(PAN)).thenReturn(Optional.of(expected));

        // Act
        Card actual = service.getCard(PAN);

        // Assert
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void shouldRejectUnknownCard() {
        // Arrange
        when(cards.findByPan(PAN)).thenReturn(Optional.empty());

        // Act / Assert
        assertThatThrownBy(() -> service.getCard(PAN)).isInstanceOf(CardNotFoundException.class);
    }

    @Test
    void shouldApplyDefaultPagination() {
        // Arrange
        when(defaults.pageLimit()).thenReturn(10);
        when(defaults.pageOffset()).thenReturn(0L);
        when(cards.findCards(10, 0L, null, null, null, null, null)).thenReturn(List.of(card()));

        // Act
        List<Card> result = service.getCards(null, null, null, null, null, null, null);

        // Assert
        assertThat(result).containsExactly(card());
    }

    @Test
    void shouldRejectPageAboveConfiguredLimit() {
        // Arrange
        when(settings.maxPageLimit()).thenReturn(100);

        // Act / Assert
        assertThatThrownBy(() -> service.getCards(101, 0L, null, null, null, null, null))
                .isInstanceOf(TooLargeLimitException.class);
    }

    @Test
    void shouldPersistReservationAndReducedBalance() {
        // Arrange: запускаем переданную операцию без настоящей БД.
        when(transactions.runSupplier(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(card()));
        when(reservations.isUnique("012345678901", PAN)).thenReturn(true);
        when(reservations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cards.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        Card result = service.reserve(PAN, new BigDecimal("100"), "012345678901");

        // Assert
        assertThat(result.availableBalance()).isEqualByComparingTo("900");
        ArgumentCaptor<Card> saved = ArgumentCaptor.forClass(Card.class);
        verify(cards).update(saved.capture());
        assertThat(saved.getValue().availableBalance()).isEqualByComparingTo("900");
        verify(events).notifyListeners(isA(CardServiceReserveEvent.class));
    }

    @Test
    void shouldNotDebitBalanceForDuplicateReservation() {
        // Arrange
        when(transactions.runSupplier(any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(0).get());
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(card()));
        when(reservations.isUnique("012345678901", PAN)).thenReturn(false);

        // Act / Assert
        assertThatThrownBy(() -> service.reserve(PAN, new BigDecimal("100"), "012345678901"))
                .isInstanceOf(ReservationAlreadyExistsException.class);
        verify(cards, never()).update(any());
        verify(reservations, never()).save(any());
    }

    private Card card() {
        return new Card(UUID.fromString("00000000-0000-0000-0000-000000000001"), PAN, "400000", "IVAN IVANOV",
                YearMonth.of(2027, 1), CardStatus.ACTIVE, "643", new BigDecimal("500"), new BigDecimal("2000"),
                new BigDecimal("1000"), "ISS001", Instant.parse("2026-11-20T12:00:00Z"));
    }
}
