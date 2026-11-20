package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static com.processing.api.support.Api.gateway;
import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

// Источники: tz/04-authorization.md; AuthServiceImpl, DeclineOutcome, AuthControllerImpl.
class AuthorizationApiTest extends ApiTest {
    @Test
    void shouldApproveReserveAndBecomeSearchable() {
        // Given
        String pan = createCard().path("pan");
        Map<String, Object> request = Data.payment(pan);

        // When
        Response response = gateway().body(request).post("/api/transactions");

        // Then
        response.then().statusCode(200).body(matchesJsonSchemaInClasspath("schemas/authorization.json"))
                .body("status", equalTo("APPROVED"), "responseCode", equalTo("00"),
                        "stan", equalTo(request.get("stan")), "rrn", matchesPattern("\\d{12}"),
                        "authCode", matchesPattern("[A-Z0-9]{6}"), "declineReason", nullValue(),
                        "processingTimeMs", greaterThanOrEqualTo(0));
        balance(pan, Integer.getInteger("probe.balance", 900));
        awaitLogged(pan, (String) request.get("stan"));
    }

    @ParameterizedTest
    @CsvSource({"BLOCKED,05,CARD_BLOCKED", "INACTIVE,05,CARD_INACTIVE", "EXPIRED,54,CARD_EXPIRED"})
    void shouldDeclineCardStatus(String status, String code, String reason) {
        // Given
        String pan = createCard().path("pan");
        gateway().body(Map.of("status", status)).patch("/api/cards/{pan}", pan)
                .then().statusCode(200).body("status", equalTo(status));
        Map<String, Object> request = Data.payment(pan);

        // When
        Response response = gateway().body(request).post("/api/transactions");

        // Then
        decline(response, 200, code, reason);
        balance(pan, 1000);
        awaitLogged(pan, (String) request.get("stan"));
    }

    @Test
    void shouldDeclineActiveCardWithDateAfterExpiryMonth() {
        // Given
        Response card = createCard();
        String pan = card.path("pan");
        String expiry = card.path("expiryDate");
        YearMonth month = YearMonth.parse(expiry);
        Map<String, Object> request = Data.changed(Data.payment(pan), "transmissionDateTime",
                month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString());

        // When
        Response response = gateway().body(request).post("/api/transactions");

        // Then
        decline(response, 200, "54", "CARD_EXPIRED");
        balance(pan, 1000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"400000", "999999"})
    void shouldDistinguishMissingCardAndMissingBinPaths(String bin) {
        // Given
        Map<String, Object> request = Data.payment(Data.unusedPan(bin));

        // When
        Response response = gateway().body(request).post("/api/transactions");

        // Then
        decline(response, 200, "14", "CARD_NOT_FOUND");
    }

    @ParameterizedTest
    @CsvSource({"99,00,APPROVED,1", "100,00,APPROVED,0", "101,51,DECLINED,100"})
    void shouldCheckAvailableBalanceBoundary(int amount, String code, String status, int expectedBalance) {
        // Given
        String pan = createCard(Data.changed(Data.card(), "initialBalance", 100)).path("pan");

        // When
        Response response = gateway().body(Data.changed(Data.payment(pan), "amount", amount)).post("/api/transactions");

        // Then
        response.then().statusCode(200).body("responseCode", equalTo(code), "status", equalTo(status));
        balance(pan, expectedBalance);
    }

    @ParameterizedTest
    @CsvSource({"499,00,APPROVED,", "500,00,APPROVED,", "501,61,DECLINED,EXCEEDS_AMOUNT_LIMIT"})
    void shouldCheckDailyLimitBoundary(int amount, String code, String status, String reason) {
        // Given
        String pan = createCard().path("pan");

        // When
        Response response = gateway().body(Data.changed(Data.payment(pan), "amount", amount)).post("/api/transactions");

        // Then
        response.then().statusCode(200).body("responseCode", equalTo(code), "status", equalTo(status),
                "declineReason", equalTo(reason));
    }

    @ParameterizedTest
    @CsvSource({"199,00,APPROVED,", "200,00,APPROVED,", "201,61,DECLINED,EXCEEDS_AMOUNT_LIMIT"})
    void shouldCheckMonthlyLimitOnAnotherDay(int amount, String code, String status, String reason) {
        // Given
        String pan = createCard(Data.changed(Data.card(), "monthlyLimit", 600)).path("pan");
        Instant firstDay = YearMonth.now(ZoneOffset.UTC).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<String, Object> first = Data.changed(Data.payment(pan), "amount", 400);
        first.put("transmissionDateTime", firstDay.toString());
        gateway().body(first).post("/api/transactions").then().statusCode(200).body("responseCode", equalTo("00"));
        Map<String, Object> second = Data.changed(Data.payment(pan), "amount", amount);
        // Оба дня гарантированно внутри одного месяца, в том числе при запуске в последний день.
        second.put("transmissionDateTime", firstDay.plus(1, ChronoUnit.DAYS).toString());

        // When
        Response response = gateway().body(second).post("/api/transactions");

        // Then
        response.then().statusCode(200).body("responseCode", equalTo(code), "status", equalTo(status),
                "declineReason", equalTo(reason));
    }

    @ParameterizedTest
    @CsvSource({"BLOCKED,403,05,CARD_BLOCKED", "INACTIVE,403,05,CARD_INACTIVE", "EXPIRED,403,54,CARD_EXPIRED"})
    void shouldKeepInternalHttpStatusDistinctFromGateway(String status, int http, String code, String reason) {
        // Given
        String pan = createCard().path("pan");
        gateway().body(Map.of("status", status)).patch("/api/cards/{pan}", pan).then().statusCode(200);

        // When
        Response response = given().spec(Api.AUTH).body(Data.payment(pan)).post("/api/internal/authorize");

        // Then
        decline(response, http, code, reason);
    }

    @Test
    void shouldAuthorizeThroughInternalEndpoint() {
        // Given
        String pan = createCard().path("pan");

        // When
        Response response = given().spec(Api.AUTH).body(Data.payment(pan)).post("/api/internal/authorize");

        // Then
        response.then().statusCode(200).body("responseCode", equalTo("00"), "rrn", matchesPattern("\\d{12}"));
        balance(pan, 900);
    }

    @Test
    void shouldRouteThroughInternalSwitchEndpoint() {
        // Given
        String pan = createCard().path("pan");
        Map<String, Object> request = Data.payment(pan);

        // When
        Response response = given().spec(Api.SWITCH).body(request).post("/api/internal/route");

        // Then
        response.then().statusCode(200).body("responseCode", equalTo("00"), "stan", equalTo(request.get("stan")));
        awaitLogged(pan, (String) request.get("stan"));
    }

    static void decline(Response response, int http, String code, String reason) {
        response.then().statusCode(http).body("status", equalTo("DECLINED"), "responseCode", equalTo(code),
                "declineReason", equalTo(reason), "rrn", nullValue(), "authCode", nullValue());
    }
}
