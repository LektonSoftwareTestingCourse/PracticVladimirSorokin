package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

// Источник: ограничения AuthorizationRequest/RollbackRequest. Дефект отсутствия деталей ошибки — в отчёте D-04.
class AuthorizationValidationApiTest extends ApiTest {
    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidAuthorizationFields")
    void shouldRejectInvalidAuthorizationField(String field, Object value) {
        // Given
        Map<String, Object> request = Data.changed(Data.payment(Data.missingPan()), field, value);

        // When
        Response response = given().spec(Api.AUTH).body(request).post("/api/internal/authorize");

        // Then: текущий обработчик Spring возвращает общую ошибку, без имени нарушенного поля.
        response.then().statusCode(400).body("status", equalTo(400), "error", equalTo("Bad Request"),
                "path", equalTo("/api/internal/authorize"));
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidRollbackFields")
    void shouldRejectInvalidRollbackField(String field, Object value) {
        // Given
        Map<String, Object> request = Data.changed(
                Map.of("pan", Data.missingPan(), "rrn", Data.rrn(), "amount", 100), field, value);

        // When
        Response response = given().spec(Api.AUTH).body(request).post("/api/internal/rollback");

        // Then
        response.then().statusCode(400).body("status", equalTo(400), "error", equalTo("Bad Request"),
                "path", equalTo("/api/internal/rollback"));
    }

    static Stream<Arguments> invalidAuthorizationFields() {
        Stream<Arguments> required = Stream.of("mti", "stan", "pan", "processingCode", "amount", "currencyCode",
                "transmissionDateTime", "terminalId", "merchantId", "mcc", "acquirerId", "issuerId")
                .map(field -> Arguments.of(field, null));
        Stream<Arguments> strings = Stream.of("mti", "stan", "pan", "processingCode", "currencyCode", "terminalId",
                "merchantId", "mcc", "acquirerId", "issuerId").flatMap(field -> Stream.of(Arguments.of(field, ""),
                        Arguments.of(field, " ")));
        return Stream.concat(Stream.concat(required, strings), Stream.of(
                Arguments.of("mti", "010"), Arguments.of("mti", "01000"),
                Arguments.of("stan", "12345"), Arguments.of("stan", "1234567"),
                Arguments.of("pan", "400000123456789"), Arguments.of("pan", "40000012345678901"),
                Arguments.of("processingCode", "12345"), Arguments.of("processingCode", "1234567"),
                Arguments.of("currencyCode", "64"), Arguments.of("currencyCode", "6430"),
                Arguments.of("terminalId", "1234567"), Arguments.of("terminalId", "123456789"),
                Arguments.of("merchantId", "12345678901234"), Arguments.of("merchantId", "1234567890123456"),
                Arguments.of("mcc", "541"), Arguments.of("mcc", "54111"), Arguments.of("amount", -1)));
    }

    static Stream<Arguments> invalidRollbackFields() {
        return Stream.of(Arguments.of("rrn", null), Arguments.of("rrn", ""), Arguments.of("rrn", "12345678901"),
                Arguments.of("rrn", "1234567890123"), Arguments.of("rrn", "ABCDEFGHIJKL"),
                Arguments.of("pan", null), Arguments.of("pan", ""), Arguments.of("pan", "400000123456789"),
                Arguments.of("pan", "40000012345678901"), Arguments.of("pan", "400000123456789X"),
                Arguments.of("amount", null), Arguments.of("amount", -1), Arguments.of("amount", 0));
    }
}
