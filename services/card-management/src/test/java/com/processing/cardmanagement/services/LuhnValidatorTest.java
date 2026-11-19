package com.processing.cardmanagement.services;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LuhnValidatorTest {
    private final LuhnValidator validator = new LuhnValidator();

    @ParameterizedTest
    @CsvSource({"4000000000000002,true", "4111111111111111,true", "4111111111111112,false"})
    void shouldCheckKnownControlDigits(String pan, boolean expected) {
        // Act
        boolean valid = validator.isValid(pan);

        // Assert
        assertThat(valid).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"400000", "400001"})
    void shouldGenerateSixteenDigitsWithRequestedBinAndValidChecksum(String bin) {
        // Act
        String pan = validator.generatePan(bin);

        // Assert
        assertThat(pan).matches("\\d{16}").startsWith(bin);
        assertThat(validator.isValid(pan)).isTrue();
    }
}
