package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Map;

import static com.processing.api.support.Api.gateway;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

// Источники: CardController, ReserveRequest, RollbackRequest; tz/05-card-management.md.
class ReservationApiTest extends ApiTest {
    @ParameterizedTest
    @CsvSource({"reserve,-1", "reserve,0", "rollback,-1", "rollback,0"})
    void shouldRejectNonPositiveAmount(String operation, int amount) {
        // Given
        String pan = createCard().path("pan");

        // When
        Response response = gateway().body(Map.of("pan", pan, "amount", amount, "rrn", Data.rrn()))
                .post("/api/cards/" + pan + "/" + operation);

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"),
                "error", equalTo("MethodArgumentNotValidException"), "message", containsString("greater than 0"));
        balance(pan, 1000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345678901", "1234567890123", "ABCDEFGHIJKL"})
    void shouldValidateRrnFormat(String rrn) {
        // Given
        String pan = createCard().path("pan");

        // When
        Response response = gateway().body(Map.of("amount", 100, "rrn", rrn))
                .post("/api/cards/{pan}/reserve", pan);

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"),
                "error", equalTo("MethodArgumentNotValidException"), "message", notNullValue());
        balance(pan, 1000);
    }

    @Test
    void shouldReserveAndRejectSameRrnWithoutSecondDebit() {
        // Given
        String pan = createCard().path("pan");
        Map<String, Object> reserve = Map.of("amount", 100, "rrn", Data.rrn());
        gateway().body(reserve).post("/api/cards/{pan}/reserve", pan).then().statusCode(200)
                .body("availableBalance", equalTo(900));

        // When
        Response response = gateway().body(reserve).post("/api/cards/{pan}/reserve", pan);

        // Then
        response.then().statusCode(409).body("serviceName", equalTo("card-management"),
                "error", equalTo("ReservationAlreadyExistsException"));
        balance(pan, 900);
    }

    @Test
    void shouldRefundOnlyOnceAndNotCreatePaymentLog() {
        // Given
        String pan = createCard().path("pan");
        String rrn = Data.rrn();
        gateway().body(Map.of("amount", 100, "rrn", rrn)).post("/api/cards/{pan}/reserve", pan)
                .then().statusCode(200).body("availableBalance", equalTo(900));
        Map<String, Object> rollback = Map.of("pan", pan, "amount", 100, "rrn", rrn);

        // When
        Response response = gateway().body(rollback).post("/api/cards/{pan}/rollback", pan);

        // Then
        response.then().statusCode(200).body("pan", equalTo(pan), "availableBalance", equalTo(1000));
        gateway().body(rollback).post("/api/cards/{pan}/rollback", pan).then().statusCode(409)
                .body("serviceName", equalTo("card-management"), "error", equalTo("RollbackAlreadySatisfiedException"));
        balance(pan, 1000);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(4)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> gateway().queryParam("pan", pan).get("/api/transactions/search")
                        .then().statusCode(200).body("total", equalTo(0)));
    }

    @Test
    void shouldReturn402WithoutChangingBalance() {
        // Given
        String pan = createCard().path("pan");

        // When
        Response response = gateway().body(Map.of("amount", 1001, "rrn", Data.rrn()))
                .post("/api/cards/{pan}/reserve", pan);

        // Then
        response.then().statusCode(402).body("serviceName", equalTo("card-management"),
                "error", equalTo("InsufficientFundsException"));
        balance(pan, 1000);
    }

    @Test
    void shouldRejectReserveForBlockedCard() {
        // Given
        String pan = createCard().path("pan");
        gateway().body(Map.of("status", "BLOCKED")).patch("/api/cards/{pan}", pan).then().statusCode(200)
                .body("status", equalTo("BLOCKED"));

        // When
        Response response = gateway().body(Map.of("amount", 100, "rrn", Data.rrn()))
                .post("/api/cards/{pan}/reserve", pan);

        // Then
        response.then().statusCode(409).body("serviceName", equalTo("card-management"),
                "error", equalTo("IllegalStateException"));
        balance(pan, 1000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reserve", "rollback"})
    void shouldReturn404ForUnknownCard(String operation) {
        // Given
        String pan = Data.missingPan();

        // When
        Response response = gateway().body(Map.of("pan", pan, "rrn", Data.rrn(), "amount", 100))
                .post("/api/cards/" + pan + "/" + operation);

        // Then
        response.then().statusCode(404).body("serviceName", equalTo("card-management"),
                "error", equalTo("CardNotFoundException"));
    }

    @Test
    void shouldReturn404ForUnknownReservation() {
        // Given
        String pan = createCard().path("pan");

        // When
        Response response = gateway().body(Map.of("pan", pan, "rrn", Data.rrn(), "amount", 100))
                .post("/api/cards/{pan}/rollback", pan);

        // Then
        response.then().statusCode(404).body("serviceName", equalTo("card-management"),
                "error", equalTo("ReservationNotFoundException"));
    }

    @Test
    void shouldRollbackAuthorizationAndRejectRepeat() {
        // Given
        String pan = createCard().path("pan");
        String rrn = given().spec(Api.AUTH).body(Data.payment(pan)).post("/api/internal/authorize")
                .then().statusCode(200).body("responseCode", equalTo("00")).extract().path("rrn");
        Map<String, Object> rollback = Map.of("pan", pan, "amount", 100, "rrn", rrn);

        // When
        Response response = given().spec(Api.AUTH).body(rollback).post("/api/internal/rollback");

        // Then
        response.then().statusCode(200).body("status", equalTo("APPROVED"), "responseCode", equalTo("00"),
                "rrn", equalTo(rrn));
        given().spec(Api.AUTH).body(rollback).post("/api/internal/rollback").then().statusCode(409)
                .body("responseCode", equalTo("05"), "declineReason", equalTo("ALREADY_ROLLED_BACK"));
        balance(pan, 1000);
    }
}
