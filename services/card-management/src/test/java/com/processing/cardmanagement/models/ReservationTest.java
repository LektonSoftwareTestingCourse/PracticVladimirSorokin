package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {
    @Test
    void shouldMarkReservationRolledBack() {
        // Arrange
        Reservation reservation = reservation();
        ReservationRollback rollback = reservation.startRollback(new BigDecimal("100"));

        // Act
        Reservation updated = reservation.rolledBack(rollback);

        // Assert
        assertThat(updated.status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(rollback.reservationId()).isEqualTo(reservation.id());
        assertThat(rollback.rollbackAmount()).isEqualByComparingTo("100");
    }

    @Test
    void shouldRejectSecondRollback() {
        // Arrange
        Reservation reservation = reservation();
        Reservation updated = reservation.rolledBack(reservation.startRollback(new BigDecimal("100")));

        // Act / Assert
        assertThatThrownBy(() -> updated.startRollback(BigDecimal.ONE))
                .isInstanceOf(RollbackAlreadySatisfiedException.class);
    }

    @Test
    void shouldRejectRollbackFromAnotherReservation() {
        // Arrange
        Reservation reservation = reservation();
        ReservationRollback other = new ReservationRollback(UUID.randomUUID(), UUID.randomUUID(),
                reservation.pan(), BigDecimal.ONE, reservation.rrn(), reservation.createdAt());

        // Act / Assert
        assertThatThrownBy(() -> reservation.rolledBack(other)).isInstanceOf(IllegalArgumentException.class);
    }

    private Reservation reservation() {
        Instant created = Instant.parse("2026-11-20T12:00:00Z");
        return new Reservation(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "4000001234567890", new BigDecimal("100"), "012345678901", ReservationStatus.RESERVED, created, created);
    }
}
