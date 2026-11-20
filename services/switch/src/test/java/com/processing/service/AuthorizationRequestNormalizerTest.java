package com.processing.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationRequestNormalizerTest {
    @ParameterizedTest
    @CsvSource({"T, T0000000", "TERM0001, TERM0001", "TERM00012, TERM0001"})
    void shouldPadOrTruncateTerminalId(String input, String expected) {
        // Act
        String result = AuthorizationRequestNormalizer.normalizeTerminalId(input);

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"M,M00000000000000", "MERCH0000000001,MERCH0000000001",
            "MERCH00000000001,MERCH0000000001", "OTHER123456789012,OTHER1234567890",
            "MERCH123456789012,MERCH1234567890"})
    void shouldNormalizeMerchantId(String input, String expected) {
        // Act
        String result = AuthorizationRequestNormalizer.normalizeMerchantId(input);

        // Assert
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @CsvSource({"' '"})
    void shouldKeepMissingTerminalForDownstreamValidation(String value) {
        // Act
        String terminal = AuthorizationRequestNormalizer.normalizeTerminalId(value);

        // Assert
        assertThat(terminal).isEqualTo(value);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @CsvSource({"' '"})
    void shouldKeepMissingMerchantForDownstreamValidation(String value) {
        // Act
        String merchant = AuthorizationRequestNormalizer.normalizeMerchantId(value);

        // Assert
        assertThat(merchant).isEqualTo(value);
    }
}
