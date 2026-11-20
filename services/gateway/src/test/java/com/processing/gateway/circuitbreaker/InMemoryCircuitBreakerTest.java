package com.processing.gateway.circuitbreaker;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InMemoryCircuitBreakerTest {
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    void shouldOpenAfterConfiguredNumberOfFailures() {
        // Arrange
        InMemoryCircuitBreaker breaker = InMemoryCircuitBreaker.forTesting(
                Duration.ofSeconds(10), 2, Clock.fixed(NOW, ZoneOffset.UTC));
        breaker.recordFailure("cms");
        assertThat(breaker.allowRequest("cms")).isTrue();

        // Act
        breaker.recordFailure("cms");

        // Assert
        assertThat(breaker.allowRequest("cms")).isFalse();
        assertThat(breaker.allowRequest("switch")).isTrue();
    }

    @Test
    void shouldAllowFirstProbeAtTimeout() {
        // Arrange
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        InMemoryCircuitBreaker breaker = InMemoryCircuitBreaker.forTesting(Duration.ofSeconds(10), 1, clock);
        breaker.recordFailure("cms");
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));

        // Act
        boolean firstProbe = breaker.allowRequest("cms");

        // Assert
        assertThat(firstProbe).isTrue();
    }

    @Test
    void shouldRejectSecondProbeWhileFirstIsInProgress() {
        // Arrange
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        InMemoryCircuitBreaker breaker = InMemoryCircuitBreaker.forTesting(Duration.ofSeconds(10), 1, clock);
        breaker.recordFailure("cms");
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        breaker.allowRequest("cms");

        // Act
        boolean secondProbe = breaker.allowRequest("cms");

        // Assert
        assertThat(secondProbe).isFalse();
    }

    @Test
    void shouldCloseCircuitAfterSuccessfulProbe() {
        // Arrange
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(NOW);
        InMemoryCircuitBreaker breaker = InMemoryCircuitBreaker.forTesting(Duration.ofSeconds(10), 1, clock);
        breaker.recordFailure("cms");
        when(clock.instant()).thenReturn(NOW.plusSeconds(10));
        breaker.allowRequest("cms");

        // Act
        breaker.recordSuccess("cms");

        // Assert
        assertThat(breaker.allowRequest("cms")).isTrue();
    }
}
