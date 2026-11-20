package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

// GlobalExceptionHandler Logger: DataAccessException -> HTTP 503 с retryAfterMs=1000.
// Скрипт останавливает отдельную БД Logger; проверки и подготовка данных выполняются по HTTP.
@Tag("database-fault")
class DatabaseFailureApiTest extends ApiTest {
    @Test
    void shouldPrepareMessageBeforeDatabaseFailure() {
        // Given
        Map<String, Object> message = Data.transaction(probePan());

        // When
        Response response = given().spec(Api.LOGGER).body(message).post("/api/internal/log");

        // Then
        response.then().statusCode(201).body("id", equalTo(message.get("id")), "status", equalTo("stored"));
    }

    @Test
    void shouldReturn503WhenWritingWithoutDatabase() {
        // Given
        Map<String, Object> message = Data.transaction(Data.missingPan());

        // When
        Response response = given().spec(Api.LOGGER).body(message).post("/api/internal/log");

        // Then
        databaseError(response);
    }

    @ParameterizedTest(name = "{index}: {1}")
    @MethodSource("readEndpoints")
    void shouldReturn503WhenReadingWithoutDatabase(RequestSpecification specification, String path) {
        // Given / When / Then: ждём пробного окна circuit breaker, чтобы получить именно ответ Logger.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
                databaseError(given().spec(specification).get(path)));
    }

    @Test
    void shouldRecoverWithPreviousMessageAndAcceptNewWrites() {
        // Given
        String pan = probePan();
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                given().spec(Api.LOGGER).queryParam("pan", pan).get("/api/transactions/search").then().statusCode(200)
                        .body("total", equalTo(1), "transactions", hasSize(1), "transactions[0].pan", equalTo(pan)));
        Map<String, Object> message = Data.transaction(pan);

        // When
        Response response = given().spec(Api.LOGGER).body(message).post("/api/internal/log");

        // Then
        response.then().statusCode(201).body("id", equalTo(message.get("id")));
        given().spec(Api.LOGGER).queryParam("pan", pan).get("/api/transactions/search").then().statusCode(200)
                .body("total", equalTo(2), "transactions", hasSize(2));
    }

    static Stream<Arguments> readEndpoints() {
        return Stream.of(Api.LOGGER, Api.GATEWAY).flatMap(specification -> Stream.of(
                "/api/transactions/search", "/api/transactions/export",
                "/api/dashboard/stats", "/api/dashboard/recent", "/api/dashboard/charts")
                .map(path -> Arguments.of(specification, path)));
    }

    private static String probePan() {
        return Objects.requireNonNull(System.getProperty("probe.databasePan"));
    }

    private static void databaseError(Response response) {
        response.then().statusCode(503).body("serviceName", equalTo("transaction-logger"),
                "error", equalTo("Database operation failed"),
                "message", equalTo("Transaction logger database is unavailable"), "retryAfterMs", equalTo("1000"));
    }
}
