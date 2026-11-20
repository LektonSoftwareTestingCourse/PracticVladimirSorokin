package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardTest {
    @ParameterizedTest
    @CsvSource({"99,1", "100,0"})
    void shouldReserveUpToAvailableBalance(String amount, String remaining) {
        // Arrange
        Card card = card(CardStatus.ACTIVE);
        Reservation reservation = card.startReservation(new BigDecimal(amount), "012345678901");

        // Act
        Card updated = card.withReservation(reservation);

        // Assert
        assertThat(updated.availableBalance()).isEqualByComparingTo(remaining);
        assertThat(card.availableBalance()).isEqualByComparingTo("100");
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void shouldRejectAmountOneKopeckAboveBalance() {
        // Arrange
        Card card = card(CardStatus.ACTIVE);

        // Act / Assert
        assertThatThrownBy(() -> card.startReservation(new BigDecimal("101"), "012345678901"))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @ParameterizedTest
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void shouldRejectReservationForNonActiveCard(CardStatus status) {
        // Arrange
        Card card = card(status);

        // Act / Assert
        assertThatThrownBy(() -> card.startReservation(BigDecimal.ONE, "012345678901"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(status.name());
    }

    @ParameterizedTest
    @CsvSource({"-1,100", "100,-1", "101,100"})
    void shouldRejectNegativeOrInvertedLimits(String daily, String monthly) {
        // Arrange
        Card card = card(CardStatus.ACTIVE);

        // Act / Assert
        assertThatThrownBy(() -> card.withData(CardStatus.ACTIVE, new BigDecimal(daily),
                new BigDecimal(monthly), new BigDecimal("100"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldDeleteCardWithoutChangingItsMoney() {
        // Arrange
        Card card = card(CardStatus.ACTIVE);

        // Act
        Card deleted = card.deleted();

        // Assert
        assertThat(deleted.status()).isEqualTo(CardStatus.DELETED);
        assertThat(deleted.availableBalance()).isEqualTo(card.availableBalance());
        assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
    }

    @Test
    void shouldRejectDeletingAlreadyDeletedCard() {
        // Arrange
        Card deleted = card(CardStatus.DELETED);

        // Act / Assert
        assertThatThrownBy(deleted::deleted).isInstanceOf(IllegalStateException.class);
    }

    private Card card(CardStatus status) {
        return new Card(UUID.fromString("00000000-0000-0000-0000-000000000001"), "4000001234567890", "400000",
                "IVAN IVANOV", YearMonth.of(2027, 1), status, "643", new BigDecimal("500"),
                new BigDecimal("2000"), new BigDecimal("100"), "ISS001", Instant.parse("2026-11-20T12:00:00Z"));
    }
}
