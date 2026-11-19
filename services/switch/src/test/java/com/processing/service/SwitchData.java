package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.config.SwitchProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class SwitchData {
    static final String PAN = "4000001234567890";

    private SwitchData() {
    }

    static AuthorizationRequest request() {
        return AuthorizationRequest.builder()
                .mti("0100").stan("000001").pan(PAN).processingCode("000000")
                .amount(new BigDecimal("100")).currencyCode("643")
                .transmissionDateTime(Instant.parse("2026-11-20T12:00:00Z"))
                .terminalId("TERM0001").terminalType("POS").merchantId("MERCH0000000001")
                .mcc("5411").acquirerId("ACQ001").issuerId("ISS001").build();
    }

    static AuthorizationResponse approved() {
        return new AuthorizationResponse("0110", "000001", "012345678901", "ABC123", "00", "APPROVED", null, 5);
    }

    static SwitchProperties properties() {
        return new SwitchProperties("1", Map.of("400000", "ISS001", "400001", "ISS002"),
                "http://auth", "http://logger", "http://merchant",
                new SwitchProperties.HttpProperties(100, 100, 100),
                new SwitchProperties.RetryProperties(1, List.of(1L)),
                new SwitchProperties.CircuitBreakerSection(
                        new SwitchProperties.CircuitBreakerProperties(50, 10, 2, 1000)));
    }
}
