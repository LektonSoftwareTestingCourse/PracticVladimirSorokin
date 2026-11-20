package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.processing.api.support.Api.gateway;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

// Источники: CreateCardRequest, CardController, CardGeneratorController, GlobalExceptionHandler CMS; tz/05.
class CardsApiTest extends ApiTest {
    @Test
    void shouldCreateCardWithExplicitFinancialState() {
        // Given / When
        Response response = gateway().body(Data.card()).post("/api/cards");

        // Then
        response.then().statusCode(201).body("pan", matchesPattern("400000\\d{10}"), "id", notNullValue(),
                "status", equalTo("ACTIVE"), "currencyCode", equalTo("643"), "dailyLimit", equalTo(500),
                "monthlyLimit", equalTo(2000), "availableBalance", equalTo(1000),
                "expiryDate", matchesPattern("\\d{4}-(0[1-9]|1[0-2])"), "issuerId", equalTo("ISS001"));
    }

    @Test
    void shouldReadSameCardThroughGatewayCache() {
        // Given
        String pan = createCard().path("pan");
        gateway().get("/api/cards/{pan}", pan).then().statusCode(200).body("pan", equalTo(pan));

        // When
        Response response = gateway().get("/api/cards/{pan}", pan);

        // Then
        response.then().statusCode(200).header("X-Cache", equalTo("HIT"))
                .body("pan", equalTo(pan), "availableBalance", equalTo(1000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"IVAN IVANOV", "ANNA-MARIA O'NEIL", "A. IVANOV"})
    void shouldAcceptAllowedCardholderCharacters(String name) {
        // Given / When
        Response response = gateway().body(Data.changed(Data.card(), "cardholderName", name)).post("/api/cards");

        // Then
        response.then().statusCode(201).body("cardholderName", equalTo(name), "status", equalTo("ACTIVE"));
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidCreateFields")
    void shouldRejectInvalidCreationField(String field, Object value, String rule) {
        // Given
        Map<String, Object> request = Data.changed(Data.card(), field, value);

        // When
        Response response = gateway().body(request).post("/api/cards");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"),
                "error", equalTo("MethodArgumentNotValidException"), "message", matchesPattern("(?s).*(" + rule + ").*"));
    }

    @Test
    void shouldRejectUnknownBin() {
        // Given / When
        Response response = gateway().body(Data.changed(Data.card(), "bin", "999999")).post("/api/cards");

        // Then
        response.then().statusCode(404).body("serviceName", equalTo("card-management"), "error", equalTo("BinNotFoundException"));
    }

    @ParameterizedTest
    @CsvSource({"GET,1", "GET,400000123456789", "GET,40000012345678901", "GET,400000123456789X",
            "PATCH,1", "PATCH,400000123456789", "PATCH,40000012345678901", "PATCH,400000123456789X",
            "DELETE,1", "DELETE,400000123456789", "DELETE,40000012345678901", "DELETE,400000123456789X"})
    void shouldRejectInvalidPanInPath(String method, String pan) {
        // Given / When
        Response response = gateway().body(Map.of("status", "ACTIVE")).request(method, "/api/cards/" + pan);

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"),
                "message", containsString("16 digits"));
    }

    @Test
    void shouldPatchOnlyProvidedFields() {
        // Given
        String pan = createCard().path("pan");

        // When
        Response response = gateway().body(Map.of("status", "BLOCKED", "dailyLimit", 300))
                .patch("/api/cards/{pan}", pan);

        // Then
        response.then().statusCode(200).body("pan", equalTo(pan), "status", equalTo("BLOCKED"),
                "dailyLimit", equalTo(300), "monthlyLimit", equalTo(2000), "availableBalance", equalTo(1000));
    }

    @ParameterizedTest
    @CsvSource({"dailyLimit,-1", "monthlyLimit,-1", "dailyLimit,2001"})
    void shouldRejectInvalidPatchedLimits(String field, int value) {
        // Given
        String pan = createCard().path("pan");

        // When
        Response response = gateway().body(Map.of(field, value)).patch("/api/cards/{pan}", pan);

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"), "message", notNullValue());
        balance(pan, 1000);
    }

    @Test
    void shouldDeleteCardAndRejectRepeatedDeletion() {
        // Given
        String pan = createCard().path("pan");

        // When
        Response response = gateway().delete("/api/cards/{pan}", pan);

        // Then
        response.then().statusCode(204).body(equalTo(""));
        given().spec(Api.CARDS).get("/api/cards/{pan}", pan).then().statusCode(404)
                .body("error", equalTo("CardNotFoundException"), "serviceName", equalTo("card-management"));
        gateway().delete("/api/cards/{pan}", pan).then().statusCode(404)
                .body("error", equalTo("CardNotFoundException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "PATCH", "DELETE"})
    void shouldReturn404ForMissingCard(String method) {
        // Given / When
        Response response = gateway().body(Map.of("status", "ACTIVE"))
                .request(method, "/api/cards/" + Data.missingPan());

        // Then
        response.then().statusCode(404).body("serviceName", equalTo("card-management"),
                "error", equalTo("CardNotFoundException"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void shouldGenerateRequestedNumberOfCards(int count) {
        // Given / When
        Response response = gateway().body(Map.of("count", count, "bins", List.of("400000"))).post("/api/cards/generate");

        // Then
        response.then().statusCode(201).body("generated", equalTo(count), "cards", hasSize(count),
                "cards.pan", org.hamcrest.Matchers.everyItem(matchesPattern("400000\\d{10}")),
                "cards.status", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.oneOf("ACTIVE", "INACTIVE", "BLOCKED")),
                "cards.pan.unique().size()", equalTo(count));
    }

    @Test
    void shouldRejectUnknownBinDuringGeneration() {
        // Given / When
        Response response = gateway().body(Map.of("count", 1, "bins", List.of("999999"))).post("/api/cards/generate");

        // Then
        response.then().statusCode(404).body("serviceName", equalTo("card-management"),
                "error", equalTo("BinNotFoundException"));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 10001})
    void shouldRejectInvalidGenerationCount(int count) {
        // Given / When
        Response response = gateway().body(Map.of("count", count, "bins", List.of("400000")))
                .post("/api/cards/generate");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"), "message", notNullValue());
    }

    @Test
    void shouldUpdateOwnCardsAsABatch() {
        // Given
        String pan = createCard().path("pan");

        // When
        Response response = gateway().body(Map.of("pans", List.of(pan), "status", "BLOCKED"))
                .post("/api/cards/bulk-update");

        // Then
        response.then().statusCode(200).body("updated", equalTo(1));
        given().spec(Api.CARDS).get("/api/cards/{pan}", pan).then().statusCode(200).body("status", equalTo("BLOCKED"));
    }

    @Test
    void shouldRejectBatchWithoutSelector() {
        // Given / When
        Response response = gateway().body(Map.of("status", "BLOCKED")).post("/api/cards/bulk-update");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"),
                "message", equalTo("Either bin or pans must be provided"));
    }

    @Test
    void shouldRejectBothBulkSelectors() {
        // Given / When
        Response response = gateway().body(Map.of("status", "BLOCKED", "bins", List.of("400000"),
                "pans", List.of(Data.missingPan()))).post("/api/cards/bulk-update");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"),
                "message", equalTo("Only one of bin or pans must be provided"));
    }

    @ParameterizedTest
    @MethodSource("invalidGenerationBins")
    void shouldRejectInvalidGenerationBinList(Object bins) {
        // Given / When
        Response response = gateway().body(Data.changed(Map.of("count", 1, "bins", List.of("400000")), "bins", bins))
                .post("/api/cards/generate");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"), "message", notNullValue());
    }

    @ParameterizedTest
    @MethodSource("invalidBulkFields")
    void shouldRejectInvalidBulkField(String field, Object value) {
        // Given
        Map<String, Object> request = Data.bulkRequest(field, value);

        // When
        Response response = gateway().body(request).post("/api/cards/bulk-update");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"), "message", notNullValue());
    }

    static Stream<Arguments> invalidGenerationBins() {
        return Stream.of(Arguments.of((Object) null), Arguments.of(List.of()), Arguments.of(List.of("40000")),
                Arguments.of(List.of("4000000")), Arguments.of(List.of("40000X")));
    }

    static Stream<Arguments> invalidBulkFields() {
        return Stream.of(Arguments.of("status", null), Arguments.of("pans", List.of("123")),
                Arguments.of("pans", List.of("400000123456789X")), Arguments.of("bins", List.of("40000")),
                Arguments.of("bins", List.of("40000X")));
    }

    static Stream<Arguments> invalidCreateFields() {
        return Stream.of(
                Arguments.of("bin", null, "blank"), Arguments.of("bin", "", "blank|6 digits"),
                Arguments.of("bin", "40000", "6 digits"), Arguments.of("bin", "4000000", "6 digits"),
                Arguments.of("bin", "40000X", "6 digits"),
                Arguments.of("cardholderName", null, "blank"), Arguments.of("cardholderName", " ", "blank"),
                Arguments.of("cardholderName", "ivan", "uppercase"), Arguments.of("cardholderName", "ИВАН", "uppercase"),
                Arguments.of("cardholderName", "IVAN1", "uppercase"),
                Arguments.of("currencyCode", null, "blank"), Arguments.of("currencyCode", "", "blank|3|digits"),
                Arguments.of("currencyCode", "64", "3"), Arguments.of("currencyCode", "6430", "3"),
                Arguments.of("currencyCode", "ABC", "digits"),
                Arguments.of("dailyLimit", -1, "negative"), Arguments.of("monthlyLimit", -1, "negative"));
    }
}
