package com.processing.gateway.validation;

import com.processing.common.dto.authorization.AuthorizationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionRequestValidatorTest {
    private final TransactionRequestValidator validator = new TransactionRequestValidator();

    @ParameterizedTest
    @ValueSource(strings = {"0.01", "1", "1000000000"})
    void shouldAcceptPositiveAmount(String amount) {
        // Arrange
        AuthorizationRequest request = request("4000001234567890", new BigDecimal(amount), "0100", "5411", "643");

        // Act / Assert
        assertThatCode(() -> validator.validate(request)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "0"})
    void shouldRejectNonPositiveAmount(String amount) {
        // Arrange
        AuthorizationRequest request = request("4000001234567890", new BigDecimal(amount), "0100", "5411", "643");

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request)).isInstanceOf(TransactionValidationException.class)
                .hasMessage("Field 'amount' must be > 0");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "400000123456789", "40000012345678901", "400000123456789X"})
    void shouldRejectMissingOrMalformedPan(String pan) {
        // Arrange
        AuthorizationRequest request = request(pan, BigDecimal.ONE, "0100", "5411", "643");

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request)).isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("'pan'");
    }

    @ParameterizedTest
    @CsvSource({"0400,5411,643,mti", "0100,541,643,mcc", "0100,54111,643,mcc",
            "0100,ABCD,643,mcc", "0100,5411,64,currencyCode", "0100,5411,6430,currencyCode"})
    void shouldIdentifyOnlyInvalidField(String mti, String mcc, String currency, String field) {
        // Arrange
        AuthorizationRequest request = request("4000001234567890", BigDecimal.ONE, mti, mcc, currency);

        // Act / Assert
        assertThatThrownBy(() -> validator.validate(request)).isInstanceOf(TransactionValidationException.class)
                .hasMessageContaining("'" + field + "'");
    }

    @Test
    void shouldRejectMissingBody() {
        // Act / Assert
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(TransactionValidationException.class)
                .hasMessage("Request body is required");
    }

    private AuthorizationRequest request(String pan, BigDecimal amount, String mti, String mcc, String currency) {
        return new AuthorizationRequest(mti, "000001", pan, "000000", amount, currency,
                Instant.parse("2026-11-20T12:00:00Z"), "TERM0001", "POS", "MERCH0000000001", mcc, "ACQ001", null);
    }
}
