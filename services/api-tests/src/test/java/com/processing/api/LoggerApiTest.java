package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.stream.Stream;

import static com.processing.api.support.Api.gateway;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

// Источники: InternalTransactionController, TransactionRequest, TransactionMapper.matches; tz/08.
class LoggerApiTest extends ApiTest {
    @ParameterizedTest(name = "граница {0}={1}")
    @MethodSource("validBoundaries")
    void shouldAcceptMessageFieldAtValidBoundary(String field, Object value) {
        // Given
        Map<String, Object> message = Data.changed(Data.transaction(Data.missingPan()), field, value);

        // When
        Response response = given().spec(Api.LOGGER).body(message).post("/api/internal/log");

        // Then
        response.then().statusCode(201).body("id", equalTo(message.get("id")), "status", equalTo("stored"));
        given().spec(Api.LOGGER).body(message).post("/api/internal/log").then().statusCode(200)
                .body("id", equalTo(message.get("id")));
    }

    @Test
    void shouldStoreSameMessageOnlyOnce() {
        // Given
        String pan = Data.missingPan();
        Map<String, Object> message = Data.transaction(pan);
        given().spec(Api.LOGGER).body(message).post("/api/internal/log").then().statusCode(201)
                .body("id", equalTo(message.get("id")), "status", equalTo("stored"));

        // When
        Response response = given().spec(Api.LOGGER).body(message).post("/api/internal/log");

        // Then
        response.then().statusCode(200).body("id", equalTo(message.get("id")), "pan", equalTo(pan),
                "amount", equalTo(100), "status", equalTo("APPROVED"), "declineReason", nullValue());
        gateway().queryParam("pan", pan).get("/api/transactions/search").then().statusCode(200)
                .body("total", equalTo(1), "transactions", hasSize(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "stan", "pan", "status", "currencyCode"})
    void shouldRejectSameIdWithChangedBusinessData(String field) {
        // Given
        Map<String, Object> message = Data.transaction(Data.missingPan());
        given().spec(Api.LOGGER).body(message).post("/api/internal/log").then().statusCode(201)
                .body("id", equalTo(message.get("id")));
        Map<String, Object> values = Map.of("amount", 101, "stan", Data.stan(), "pan", Data.missingPan(),
                "status", "DECLINED", "currencyCode", "840");

        // When
        Response response = given().spec(Api.LOGGER).body(Data.changed(message, field, values.get(field)))
                .post("/api/internal/log");

        // Then
        response.then().statusCode(409).body("serviceName", equalTo("transaction-logger"),
                "error", equalTo("Transaction conflict"), "message", containsString(message.get("id").toString()));
        gateway().queryParam("pan", message.get("pan")).get("/api/transactions/search").then().statusCode(200)
                .body("transactions", hasSize(1), "transactions[0].amount", equalTo(100));
    }

    @Test
    void shouldTreatChangedCreatedAtAsSameBusinessMessage() {
        // Given
        Map<String, Object> message = Data.transaction(Data.missingPan());
        given().spec(Api.LOGGER).body(message).post("/api/internal/log").then().statusCode(201)
                .body("status", equalTo("stored"));

        // When
        Response response = given().spec(Api.LOGGER)
                .body(Data.changed(message, "createdAt", "2020-01-01T00:00:00Z")).post("/api/internal/log");

        // Then
        response.then().statusCode(200).body("id", equalTo(message.get("id")), "createdAt",
                org.hamcrest.Matchers.not(equalTo("2020-01-01T00:00:00Z")));
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidFields")
    void shouldRejectInvalidMessageField(String field, Object value) {
        // Given
        Map<String, Object> message = Data.changed(Data.transaction(Data.missingPan()), field, value);

        // When
        Response response = given().spec(Api.LOGGER).body(message).post("/api/internal/log");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"),
                "error", equalTo("Validation error"), "message", containsString(field), "retryAfterMs", equalTo("0"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"id\":\"not-a-uuid\"}", "{\"status\":\"LOST\"}", "null"})
    void shouldRejectUnreadableMessage(String body) {
        // Given / When
        Response response = given().spec(Api.LOGGER).body(body).post("/api/internal/log");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"),
                "error", equalTo("Invalid request body"), "message", containsString("malformed"));
    }

    @Test
    void shouldReturn405ForGetOnWriteEndpoint() {
        // Given / When
        Response response = given().spec(Api.LOGGER).get("/api/internal/log");

        // Then
        response.then().statusCode(405).body("serviceName", equalTo("transaction-logger"),
                "error", equalTo("Method not allowed"));
    }

    static Stream<Arguments> invalidFields() {
        Stream<Arguments> required = Stream.of("id", "mti", "stan", "pan", "processingCode", "amount", "currencyCode",
                "terminalId", "merchantId", "mcc", "acquirerId", "status", "transmissionDateTime", "createdAt")
                .map(field -> Arguments.of(field, null));
        Stream<Arguments> blank = Stream.of("mti", "stan", "pan", "processingCode", "currencyCode", "terminalId",
                "merchantId", "mcc", "acquirerId").flatMap(field -> Stream.of(Arguments.of(field, ""),
                        Arguments.of(field, " ")));
        return Stream.concat(Stream.concat(required, blank), Stream.of(
                Arguments.of("mti", "01000"), Arguments.of("stan", "12345"), Arguments.of("stan", "1234567"),
                Arguments.of("stan", "ABCDEF"), Arguments.of("pan", "400000123456789"),
                Arguments.of("pan", "40000012345678901"), Arguments.of("pan", "400000123456789X"),
                Arguments.of("processingCode", "12345"), Arguments.of("processingCode", "1234567"),
                Arguments.of("processingCode", "ABCDEF"), Arguments.of("amount", 0), Arguments.of("amount", -1),
                Arguments.of("currencyCode", "64"), Arguments.of("currencyCode", "6430"),
                Arguments.of("currencyCode", "ABC"), Arguments.of("terminalId", "123456789"),
                Arguments.of("terminalType", "12345678901"), Arguments.of("merchantId", "1234567890123456"),
                Arguments.of("mcc", "541"), Arguments.of("mcc", "54111"), Arguments.of("mcc", "ABCD"),
                Arguments.of("acquirerId", "12345678901"), Arguments.of("issuerId", "12345678901"),
                Arguments.of("rrn", "1234567890123"), Arguments.of("acquiringFee", -1),
                Arguments.of("declineReason", "X".repeat(101)), Arguments.of("authCode", "1234567"),
                Arguments.of("processingTimeMs", -1)));
    }

    static Stream<Arguments> validBoundaries() {
        return Stream.of(Arguments.of("mti", "1"), Arguments.of("mti", "0100"),
                Arguments.of("terminalId", "12345678"), Arguments.of("terminalType", "1234567890"),
                Arguments.of("merchantId", "123456789012345"), Arguments.of("acquirerId", "1234567890"),
                Arguments.of("issuerId", "1234567890"), Arguments.of("declineReason", "X".repeat(100)),
                Arguments.of("authCode", "123456"), Arguments.of("rrn", "123456789012"),
                Arguments.of("acquiringFee", 0), Arguments.of("processingTimeMs", 0), Arguments.of("amount", 1));
    }
}
