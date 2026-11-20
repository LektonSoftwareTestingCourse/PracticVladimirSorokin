package com.processing.api.support;

import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class Data {
    private static final AtomicInteger STANS = new AtomicInteger((int) (System.nanoTime() % 500000 + 500000));
    private static final AtomicLong RRNS = new AtomicLong(System.currentTimeMillis() % 900000000000L);

    private Data() {
    }

    public static String missingPan() {
        return unusedPan("400000");
    }

    public static String stan() {
        return String.format("%06d", Math.floorMod(STANS.incrementAndGet(), 1000000));
    }

    public static String rrn() {
        return String.format("%012d", RRNS.incrementAndGet());
    }

    public static String clientIp() {
        int n = STANS.incrementAndGet();
        return "10." + ((n >>> 16) & 255) + "." + ((n >>> 8) & 255) + "." + (n & 255);
    }

    public static String unusedPan(String bin) {
        String digits = new BigInteger(UUID.randomUUID().toString().replace("-", ""), 16).toString();
        return bin + digits.substring(0, 10);
    }

    public static Map<String, Object> card() {
        return new LinkedHashMap<>(Map.of("bin", "400000", "cardholderName", "IVAN IVANOV", "currencyCode", "643",
                "dailyLimit", 500, "monthlyLimit", 2000, "initialBalance", 1000));
    }

    public static Map<String, Object> payment(String pan) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.putAll(Map.of("mti", "0100", "stan", stan(), "pan", pan, "processingCode", "000000", "amount", 100,
                "currencyCode", "643", "transmissionDateTime", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                        .toString()));
        body.putAll(Map.of("terminalId", "TERM0001", "terminalType", "POS", "merchantId", "MERCH0000000001",
                "mcc", "5411", "acquirerId", "ACQ001", "issuerId", "ISS001"));
        return body;
    }

    public static Map<String, Object> transaction(String pan) {
        Map<String, Object> body = payment(pan);
        body.putAll(Map.of("id", UUID.randomUUID().toString(), "rrn", rrn(), "authCode", "ABC123",
                "status", "APPROVED", "processingTimeMs", 1, "acquiringFee", 0,
                "createdAt", Instant.now().toString()));
        body.put("declineReason", null);
        return body;
    }

    public static Map<String, Object> changed(Map<String, Object> source, String field, Object value) {
        Map<String, Object> body = new LinkedHashMap<>(source);
        body.put(field, value);
        return body;
    }

    public static Map<String, Object> bulkRequest(String field, Object value) {
        Map<String, Object> valid = field.equals("bins")
                ? Map.of("bins", java.util.List.of("400000"), "status", "BLOCKED")
                : Map.of("pans", java.util.List.of(missingPan()), "status", "BLOCKED");
        return changed(valid, field, value);
    }
}
