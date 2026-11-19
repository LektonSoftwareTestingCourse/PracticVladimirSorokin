package com.processing.service;

import com.processing.exception.UnknownBinException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingServiceTest {
    private final RoutingService routing = new RoutingService(SwitchData.properties());

    @ParameterizedTest
    @CsvSource({"4000001234567890,ISS001", "4000011234567890,ISS002", "400000,ISS001"})
    void shouldSelectIssuerByFirstSixDigits(String pan, String issuer) {
        // Arrange: два BIN настроены в таблице.

        // Act
        String actual = routing.getIssuerIdByPan(pan);

        // Assert
        assertThat(actual).isEqualTo(issuer);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"40000", "9999991234567890"})
    void shouldRejectMissingShortOrUnknownBin(String pan) {
        // Act / Assert
        assertThatThrownBy(() -> routing.getIssuerIdByPan(pan)).isInstanceOf(UnknownBinException.class);
    }
}
