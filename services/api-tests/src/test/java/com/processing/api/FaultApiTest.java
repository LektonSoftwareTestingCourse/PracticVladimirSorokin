package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static com.processing.api.support.Api.gateway;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

// Источники: CardManagementClientImpl.onStatus; AuthControllerImpl; AuthorizationClient; DownstreamErrorFilter.
class FaultApiTest extends ApiTest {
    @Test
    void shouldReport503WhenSwitchHealthFails() {
        // Given
        stub("GET", "/switch/health", 503, Map.of("error", "injected"));

        // When
        Response response = gateway().get("/health");

        // Then
        response.then().statusCode(503).body("status", equalTo("degraded"), "services.switch", equalTo("unavailable"),
                "service", equalTo("gateway"));
    }

    @Test
    void shouldReturn503WhenSwitchClosesConnection() {
        // Given
        given().spec(Api.FAULTS).body(List.of(Map.of("method", "POST", "path", "/switch/api/internal/route",
                "disconnect", true))).post("/__rules").then().statusCode(200).body("updated", equalTo(true));

        // When
        Response response = gateway().body(Data.payment(Data.missingPan())).post("/api/transactions");

        // Then
        response.then().statusCode(503).body("error", equalTo("SERVICE_UNAVAILABLE"),
                "serviceName", equalTo("switch"), "message", notNullValue());
    }

    @ParameterizedTest
    @CsvSource({"200,422", "500,502"})
    void shouldHandleTerminalCardListFailure(int upstreamStatus, int expectedHttp) {
        // Given
        stub("GET", "/cms/api/cards?status=ACTIVE&limit=10000", upstreamStatus, Map.of("total", 0, "cards", List.of()));

        // When
        Response response = gateway().body(Map.of("count", 1, "scenario", "normal", "tps", 100))
                .post("/api/simulator/terminal/run");

        // Then
        response.then().statusCode(expectedHttp).body("serviceName", equalTo("terminal-simulator"), "message", notNullValue());
    }

    @Test
    void shouldReturn503ForTerminalCardListConnectionFailure() {
        // Given
        given().spec(Api.FAULTS).body(List.of(Map.of("method", "GET",
                "path", "/cms/api/cards?status=ACTIVE&limit=10000", "disconnect", true)))
                .post("/__rules").then().statusCode(200).body("updated", equalTo(true));

        // When
        Response response = gateway().body(Map.of("count", 1, "scenario", "normal", "tps", 100))
                .post("/api/simulator/terminal/run");

        // Then
        response.then().statusCode(503).body("serviceName", equalTo("terminal-simulator"), "error", equalTo("Resource access"));
    }

    @ParameterizedTest
    @CsvSource({"200,404", "500,502"})
    void shouldHandleMerchantCardListFailure(int upstreamStatus, int expectedHttp) {
        // Given
        stub("GET", "/gateway/api/cards?limit=1&status=ACTIVE", upstreamStatus, Map.of("total", 0, "cards", List.of()));

        // When
        Response response = gateway().body(Map.of("count", 1, "scenario", "grocery", "tps", 100))
                .post("/api/simulator/merchant/run");

        // Then
        response.then().statusCode(expectedHttp).body("serviceName", equalTo("Merchant acquirer simulator"),
                "message", notNullValue());
    }

    @Test
    void shouldRejectMalformedJsonAtSwitch() {
        // Given / When
        Response response = given().spec(Api.SWITCH).body("{").post("/api/internal/route");

        // Then
        response.then().statusCode(400).body("status", equalTo(400), "error", equalTo("Bad Request"),
                "path", equalTo("/api/internal/route"));
    }

    @ParameterizedTest
    @CsvSource({"400,404,14,CARD_NOT_FOUND", "404,404,14,CARD_NOT_FOUND", "402,403,05,CARD_BLOCKED",
            "500,503,96,SERVICE_UNAVAILABLE", "503,503,96,SERVICE_UNAVAILABLE", "409,503,96,SERVICE_UNAVAILABLE"})
    void shouldMapCmsReadFailure(int cmsStatus, int http, String code, String reason) {
        // Given
        String pan = createCard().path("pan");
        stub("GET", "/cms/api/cards/" + pan, cmsStatus, Map.of("error", "injected CMS failure"));

        // When
        Response response = given().spec(Api.AUTH).body(Data.payment(pan)).post("/api/internal/authorize");

        // Then
        AuthorizationApiTest.decline(response, http, code, reason);
        balance(pan, 1000);
    }

    @ParameterizedTest
    @CsvSource({"400,404,14,CARD_NOT_FOUND", "404,404,14,CARD_NOT_FOUND", "402,422,51,INSUFFICIENT_FUNDS",
            "500,503,96,SERVICE_UNAVAILABLE", "503,503,96,SERVICE_UNAVAILABLE", "409,503,96,RESERVATION_FAILED"})
    void shouldMapCmsReserveFailure(int cmsStatus, int http, String code, String reason) {
        // Given
        String pan = createCard().path("pan");
        stub("POST", "/cms/api/cards/" + pan + "/reserve", cmsStatus, Map.of("error", "injected reserve failure"));

        // When
        Response response = given().spec(Api.AUTH).body(Data.payment(pan)).post("/api/internal/authorize");

        // Then
        AuthorizationApiTest.decline(response, http, code, reason);
        balance(pan, 1000);
    }

    @ParameterizedTest
    @CsvSource({"400,404,14,TRANSACTION_NOT_FOUND", "404,404,14,TRANSACTION_NOT_FOUND",
            "409,409,05,ALREADY_ROLLED_BACK", "500,503,96,SERVICE_UNAVAILABLE",
            "503,503,96,SERVICE_UNAVAILABLE", "422,503,96,ROLLBACK_FAILED"})
    void shouldMapCmsRollbackFailure(int cmsStatus, int http, String code, String reason) {
        // Given
        String pan = createCard().path("pan");
        stub("POST", "/cms/api/cards/" + pan + "/rollback", cmsStatus, Map.of("error", "injected rollback failure"));

        // When
        Response response = given().spec(Api.AUTH).body(Map.of("pan", pan, "rrn", Data.rrn(), "amount", 100))
                .post("/api/internal/rollback");

        // Then
        response.then().statusCode(http).body("responseCode", equalTo(code), "declineReason", equalTo(reason),
                "status", equalTo("DECLINED"));
        balance(pan, 1000);
    }

    @Test
    void shouldReportUnknownOutcomeForCardWithoutStatus() {
        // Given
        Response card = createCard();
        String pan = card.path("pan");
        Map<String, Object> corrupted = Data.changed(card.jsonPath().getMap(""), "status", null);
        stub("GET", "/cms/api/cards/" + pan, 200, corrupted);

        // When
        Response response = given().spec(Api.AUTH).body(Data.payment(pan)).post("/api/internal/authorize");

        // Then
        AuthorizationApiTest.decline(response, 400, "05", "UNKNOWN_REASON");
        balance(pan, 1000);
    }

    @Test
    void shouldReturn422ForInsufficientFundsFromAuthorization() {
        // Given
        String pan = createCard().path("pan");

        // When
        Response response = given().spec(Api.AUTH).body(Data.changed(Data.payment(pan), "amount", 1001))
                .post("/api/internal/authorize");

        // Then
        AuthorizationApiTest.decline(response, 422, "51", "INSUFFICIENT_FUNDS");
        balance(pan, 1000);
    }

    @Test
    void shouldTranslateUnavailableAuthorizationIntoSwitchDecline() {
        // Given
        String pan = createCard().path("pan");
        stub("POST", "/auth/api/internal/authorize", 503, Map.of("error", "injected unavailable"));

        // When
        Response response = given().spec(Api.SWITCH).body(Data.payment(pan)).post("/api/internal/route");

        // Then
        AuthorizationApiTest.decline(response, 200, "05", "Authorization service unavailable");
        balance(pan, 1000);
    }

    @Test
    void shouldHandleGatewayDependencyFailure() {
        // Given
        String pan = createCard().path("pan");
        stub("POST", "/switch/api/internal/route", 500, Map.of("error", "injected route failure"));

        // When
        Response response = gateway().body(Data.payment(pan)).post("/api/transactions");

        // Then
        response.then().statusCode(500).body("error", equalTo("injected route failure"));
        balance(pan, 1000);
    }

    static void stub(String method, String path, int status, Object body) {
        given().spec(Api.FAULTS).body(List.of(Map.of("method", method, "path", path, "status", status, "body", body)))
                .post("/__rules").then().statusCode(200).body("updated", equalTo(true));
    }
}
