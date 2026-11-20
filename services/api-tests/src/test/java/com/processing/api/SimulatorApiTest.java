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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.processing.api.support.Api.gateway;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;

// Источники: TerminalSimulatorController, TerminalRunRequest, SimulationController и их обработчики ошибок.
class SimulatorApiTest extends ApiTest {
    @ParameterizedTest
    @ValueSource(strings = {"normal", "mixed", "high_value", "night_time", "declines_test"})
    void shouldRunOneTerminalPaymentForEachScenario(String scenario) {
        // Given: список карт подменён собственными картами; авторизация и логирование настоящие.
        Response active = createCard();
        Response blocked = createCard();
        gateway().body(Map.of("status", "BLOCKED")).patch("/api/cards/{pan}", (String) blocked.path("pan"))
                .then().statusCode(200).body("status", equalTo("BLOCKED"));
        Map<String, Object> blockedBody = Data.changed(blocked.jsonPath().getMap(""), "status", "BLOCKED");
        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(cardsRule("/cms/api/cards?status=ACTIVE&limit=10000", active.jsonPath().getMap("")));
        rules.add(cardsRule("/cms/api/cards?status=ACTIVE&limit=8000", active.jsonPath().getMap("")));
        rules.add(cardsRule("/cms/api/cards?status=BLOCKED&limit=2000", blockedBody));
        given().spec(Api.FAULTS).body(rules).post("/__rules").then().statusCode(200).body("updated", equalTo(true));

        // When
        Response response = gateway().body(Map.of("count", 1, "scenario", scenario, "tps", 100))
                .post("/api/simulator/terminal/run");

        // Then
        response.then().statusCode(200).body("totalSubmitted", equalTo(1), "transactions", hasSize(1),
                "transactions[0].status", oneOf("APPROVED", "DECLINED"), "elapsedMs", greaterThanOrEqualTo(0));
        org.junit.jupiter.api.Assertions.assertEquals(1, response.jsonPath().getInt("approved")
                + response.jsonPath().getInt("declined"));
    }

    @ParameterizedTest
    @MethodSource("invalidTerminalFields")
    void shouldRejectInvalidTerminalRunField(String field, Object value) {
        // Given
        Map<String, Object> request = Data.changed(Map.of("count", 1, "scenario", "normal", "tps", 1), field, value);

        // When
        Response response = gateway().body(request).post("/api/simulator/terminal/run");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("terminal-simulator"),
                "message", org.hamcrest.Matchers.anyOf(containsString(field), containsString("TerminalScenario")));
    }

    @Test
    void shouldStartContinuousModeRejectSecondStartAndStop() {
        // Given
        Response card = createCard();
        given().spec(Api.FAULTS).body(List.of(cardsRule("/cms/api/cards?status=ACTIVE&limit=10000",
                card.jsonPath().getMap("")))).post("/__rules").then().statusCode(200).body("updated", equalTo(true));
        Map<String, Object> request = Map.of("tps", 1, "transactionType", "NORMAL");
        gateway().post("/api/simulator/terminal/stop").then().statusCode(200).body(equalTo(""));

        // When / Then
        try {
            gateway().body(request).post("/api/simulator/terminal/start-continuous").then().statusCode(200).body(equalTo(""));
            gateway().body(request).post("/api/simulator/terminal/start-continuous").then().statusCode(422)
                    .body("serviceName", equalTo("terminal-simulator"), "message", containsString("already running"));
        } finally {
            gateway().post("/api/simulator/terminal/stop").then().statusCode(200).body(equalTo(""));
        }
    }

    @ParameterizedTest
    @MethodSource("invalidContinuousFields")
    void shouldValidateContinuousRequest(String field, Object value) {
        // Given / When
        Response response = gateway().body(Data.changed(Map.of("tps", 1, "transactionType", "NORMAL"), field, value))
                .post("/api/simulator/terminal/start-continuous");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("terminal-simulator"),
                "message", org.hamcrest.Matchers.anyOf(containsString(field), containsString("TransactionType")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"grocery", "electronics", "restaurant", "travel"})
    void shouldRunMerchantScenarioAndFindItsFee(String scenario) {
        // Given
        Response card = createCard();
        String pan = card.path("pan");
        given().spec(Api.FAULTS).body(List.of(cardsRule("/gateway/api/cards?limit=1&status=ACTIVE",
                card.jsonPath().getMap("")))).post("/__rules").then().statusCode(200).body("updated", equalTo(true));

        // When
        Response response = gateway().body(Map.of("count", 1, "scenario", scenario, "tps", 100))
                .post("/api/simulator/merchant/run");

        // Then
        response.then().statusCode(200).body("totalSubmitted", equalTo(1), "transactions", hasSize(1),
                "transactions[0].status", oneOf("APPROVED", "DECLINED"));
        String stan = response.path("transactions[0].stan");
        awaitLogged(pan, stan);
        Map<String, Object> stored = gateway().queryParam("pan", pan).get("/api/transactions/search")
                .then().statusCode(200).body("transactions", hasSize(1)).extract().path("transactions[0]");
        Map<String, Object> fee = Map.of("transmissionDateTime", stored.get("transmissionDateTime"),
                "stan", stan, "pan", pan, "terminalId", stored.get("terminalId"), "amount", stored.get("amount"));
        gateway().body(fee).post("/api/simulator/merchant/fee").then().statusCode(200)
                .body("acquirerFee", notNullValue());
    }

    @ParameterizedTest
    @MethodSource("invalidMerchantFields")
    void shouldRejectInvalidMerchantRunField(String field, Object value) {
        // Given / When
        Response response = gateway().body(Data.changed(Map.of("count", 1, "scenario", "grocery", "tps", 1), field, value))
                .post("/api/simulator/merchant/run");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("Merchant acquirer simulator"),
                "message", org.hamcrest.Matchers.anyOf(containsString(field), containsString("ScenarioType")));
    }

    @Test
    void shouldReturn404ForUnknownMccGroup() {
        // Given
        createCard();

        // When
        Response response = gateway().body(Map.of("count", 1, "scenario", "grocery", "mccCodes", List.of("9999")))
                .post("/api/simulator/merchant/run");

        // Then
        response.then().statusCode(404).body("serviceName", equalTo("Merchant acquirer simulator"),
                "error", equalTo("Resource not found"));
    }

    @Test
    void shouldReturnMerchantsDirectlyAndExposeMissingGatewayRoute() {
        // Given / When
        Response response = given().spec(Api.MERCHANT).get("/api/simulator/merchants");

        // Then
        response.then().statusCode(200).body("size()", greaterThanOrEqualTo(1), "[0].mcc", notNullValue());
        gateway().get("/api/simulator/merchants").then().statusCode(404)
                .body("status", equalTo(404), "path", equalTo("/api/simulator/merchants"));
    }

    @Test
    void shouldReturn404ForUnknownFee() {
        // Given
        Map<String, Object> request = Data.payment(Data.missingPan());

        // When
        Response response = gateway().body(request).post("/api/simulator/merchant/fee");

        // Then
        response.then().statusCode(404).body("serviceName", equalTo("Merchant acquirer simulator"),
                "error", equalTo("Resource not found"), "message", containsString("fee"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pan", "stan", "terminalId", "transmissionDateTime"})
    void shouldValidateFeeRequiredFields(String field) {
        // Given / When
        Response response = gateway().body(Data.changed(Data.payment(Data.missingPan()), field, null))
                .post("/api/simulator/merchant/fee");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("Merchant acquirer simulator"),
                "message", containsString(field));
    }

    private static Map<String, Object> cardsRule(String path, Map<String, Object> card) {
        return Map.of("method", "GET", "path", path, "status", 200, "body", Map.of("total", 1, "cards", List.of(card)));
    }

    static Stream<Arguments> invalidTerminalFields() {
        return Stream.of(Arguments.of("count", 0), Arguments.of("count", -1), Arguments.of("tps", 0),
                Arguments.of("tps", -1), Arguments.of("scenario", null), Arguments.of("scenario", "UNKNOWN"));
    }

    static Stream<Arguments> invalidContinuousFields() {
        return Stream.of(Arguments.of("tps", 0), Arguments.of("tps", -1), Arguments.of("transactionType", null),
                Arguments.of("transactionType", "UNKNOWN"));
    }

    static Stream<Arguments> invalidMerchantFields() {
        return Stream.of(Arguments.of("count", 0), Arguments.of("count", -1), Arguments.of("tps", 0),
                Arguments.of("tps", -1), Arguments.of("scenario", null), Arguments.of("scenario", "UNKNOWN"));
    }
}
