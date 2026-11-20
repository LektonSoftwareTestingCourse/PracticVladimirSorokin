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

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static com.processing.api.support.Api.gateway;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

// Источники: docs/api-spec.md §1, §3; Gateway TransactionRequestValidator и TransactionValidationFilter.
class GatewayApiTest extends ApiTest {
    @Test
    void shouldReturn429WithRetryHeaderAfterTwoRequestsFromSameClient() {
        // Given: отдельный стенд настроен на capacity=2, refill=0; остальные тесты используют свои IP.
        String ip = Data.clientIp();
        given().spec(Api.GATEWAY).header("X-Forwarded-For", ip).body("null").post("/api/transactions")
                .then().statusCode(400).body("error", equalTo("VALIDATION_ERROR"));
        given().spec(Api.GATEWAY).header("X-Forwarded-For", ip).body("null").post("/api/transactions")
                .then().statusCode(400).body("error", equalTo("VALIDATION_ERROR"));

        // When
        Response response = given().spec(Api.GATEWAY).header("X-Forwarded-For", ip).body("null")
                .post("/api/transactions");

        // Then
        response.then().statusCode(429).header("Retry-After", equalTo("1"))
                .body("error", equalTo("RATE_LIMIT_EXCEEDED"), "retryAfterMs", equalTo(1000),
                        "message", equalTo("Too many requests. Try again later."));
    }

    @Test
    void shouldReportGatewayAndDependenciesHealthy() {
        // Given / When
        Response response = gateway().when().get("/health");

        // Then
        response.then().statusCode(200).contentType("application/json")
                .body("status", equalTo("ok"), "service", equalTo("gateway"), "version", notNullValue());
    }

    @Test
    void shouldRejectJsonNullBody() {
        // Given / When
        Response response = gateway().body("null").when().post("/api/transactions");

        // Then: свойство меняется только в проверке намеренной поломки.
        response.then().statusCode(Integer.getInteger("probe.gatewayStatus", 400))
                .body("error", equalTo("VALIDATION_ERROR"), "serviceName", equalTo("gateway"),
                        "message", equalTo("Request body is required"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "{\"amount\":\"abc\"}", "{\"transmissionDateTime\":\"yesterday\"}"})
    void shouldIdentifyUnreadableBody(String body) {
        // Given / When
        Response response = gateway().body(body).when().post("/api/transactions");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("gateway"), "error", equalTo("VALIDATION_ERROR"),
                "message", equalTo("Request body must be valid JSON"));
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidFields")
    void shouldRejectOneInvalidField(String field, Object value, String message) {
        // Given
        Map<String, Object> request = Data.changed(Data.payment(Data.missingPan()), field, value);

        // When
        Response response = gateway().body(request).post("/api/transactions");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("gateway"), "error", equalTo("VALIDATION_ERROR"),
                "message", equalTo(message));
    }

    @Test
    void shouldLeaveNoTransactionAfterValidationFailure() {
        // Given
        String pan = Data.missingPan();
        Map<String, Object> request = Data.changed(Data.payment(pan), "amount", 0);

        // When
        gateway().body(request).post("/api/transactions").then().statusCode(400)
                .body("message", equalTo("Field 'amount' must be > 0"));

        // Then: условие должно оставаться истинным всё окно, а не только в первом опросе.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> gateway().queryParam("pan", pan).get("/api/transactions/search")
                        .then().statusCode(200).body("total", equalTo(0), "transactions.size()", equalTo(0)));
    }

    @Test
    void shouldReturn404ForUnknownTransactionPath() {
        // Given / When
        Response response = gateway().when().get("/api/transactions/unknown-" + Data.rrn());

        // Then
        response.then().statusCode(404).body("serviceName", equalTo("transaction-logger"), "error", equalTo("Not found"));
    }

    static Stream<Arguments> invalidFields() {
        Stream<Arguments> required = Stream.of("mti", "stan", "pan", "processingCode", "currencyCode",
                "terminalId", "merchantId", "mcc", "acquirerId").flatMap(field -> Stream.of(
                        Arguments.of(field, null, "Field '" + field + "' is required"),
                        Arguments.of(field, "", "Field '" + field + "' is required"),
                        Arguments.of(field, " ", "Field '" + field + "' is required")));
        return Stream.concat(required, Stream.of(
                Arguments.of("transmissionDateTime", null, "Field 'transmissionDateTime' is required"),
                Arguments.of("amount", null, "Field 'amount' is required"),
                Arguments.of("amount", -1, "Field 'amount' must be > 0"),
                Arguments.of("amount", 0, "Field 'amount' must be > 0"),
                Arguments.of("mti", "0400", "Field 'mti' must be '0100'"),
                Arguments.of("mti", "100", "Field 'mti' must be '0100'"),
                Arguments.of("pan", "400000123456789", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("pan", "40000012345678901", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("pan", "400000123456789X", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("currencyCode", "64", "Field 'currencyCode' must be exactly 3 characters"),
                Arguments.of("currencyCode", "6430", "Field 'currencyCode' must be exactly 3 characters"),
                Arguments.of("mcc", "541", "Field 'mcc' must be exactly 4 digits"),
                Arguments.of("mcc", "54110", "Field 'mcc' must be exactly 4 digits"),
                Arguments.of("mcc", "ABCD", "Field 'mcc' must be exactly 4 digits")));
    }
}
