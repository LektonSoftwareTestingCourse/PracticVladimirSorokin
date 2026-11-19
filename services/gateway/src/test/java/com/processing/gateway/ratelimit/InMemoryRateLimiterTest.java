package com.processing.gateway.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryRateLimiterTest {
    @Test
    void shouldRejectRequestAfterBucketIsEmpty() {
        // Arrange
        InMemoryRateLimiter limiter = InMemoryRateLimiter.forTesting(1, 1, Duration.ofMinutes(1), 10, () -> 0);
        assertThat(limiter.allowRequest("client-a")).isTrue();

        // Act
        boolean allowed = limiter.allowRequest("client-a");

        // Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldRestoreTokenAtOneSecondBoundary() {
        // Arrange
        AtomicLong nanos = new AtomicLong();
        InMemoryRateLimiter limiter = InMemoryRateLimiter.forTesting(1, 1, Duration.ofMinutes(1), 10, nanos::get);
        limiter.allowRequest("client-a");
        nanos.set(999_999_999);
        assertThat(limiter.allowRequest("client-a")).isFalse();
        nanos.set(1_000_000_000);

        // Act
        boolean allowed = limiter.allowRequest("client-a");

        // Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldKeepDifferentClientsIndependent() {
        // Arrange
        InMemoryRateLimiter limiter = InMemoryRateLimiter.forTesting(1, 0, Duration.ofMinutes(1), 10, () -> 0);
        limiter.allowRequest("client-a");

        // Act
        boolean allowed = limiter.allowRequest("client-b");

        // Assert
        assertThat(allowed).isTrue();
    }
}
