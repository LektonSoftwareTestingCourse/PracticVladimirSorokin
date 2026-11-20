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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.processing.api.support.Api.gateway;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

// Источники: CardController.getCards и CardCriteriaBuilderJpaRepositoryImpl.
class CardFiltersApiTest extends ApiTest {
    @Test
    void shouldBulkUpdateOnlyOwnBin() {
        // Given
        String bin = ownBin();
        String pan = createCard(Data.changed(Data.card(), "bin", bin)).path("pan");

        // When
        Response response = gateway().body(Map.of("bins", List.of(bin), "status", "BLOCKED"))
                .post("/api/cards/bulk-update");

        // Then
        response.then().statusCode(200).body("updated", equalTo(1));
        given().spec(Api.CARDS).get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("status", equalTo("BLOCKED"));
    }

    @Test
    void shouldAcceptMaximumPageLimit() {
        // Given
        String bin = ownBin();
        String pan = createCard(Data.changed(Data.card(), "bin", bin)).path("pan");

        // When
        Response response = gateway().queryParam("bin", bin).queryParam("limit", 10000).get("/api/cards");

        // Then
        response.then().statusCode(200).body("total", equalTo(1), "cards", hasSize(1), "cards[0].pan", equalTo(pan));
    }

    @Test
    void shouldPaginateOnlyOwnBinWithoutRepeatingCards() {
        // Given
        String bin = ownBin();
        String first = createCard(Data.changed(Data.card(), "bin", bin)).path("pan");
        String second = createCard(Data.changed(Data.card(), "bin", bin)).path("pan");
        String firstPage = gateway().queryParam("bin", bin).queryParam("limit", 1).queryParam("offset", 0)
                .get("/api/cards").then().statusCode(200).body("total", equalTo(2), "cards", hasSize(1))
                .extract().path("cards[0].pan");

        // When
        Response response = gateway().queryParam("bin", bin).queryParam("limit", 1).queryParam("offset", 1)
                .get("/api/cards");

        // Then
        response.then().statusCode(200).body("total", equalTo(2), "cards", hasSize(1));
        String secondPage = response.path("cards[0].pan");
        org.junit.jupiter.api.Assertions.assertNotEquals(firstPage, secondPage);
        org.hamcrest.MatcherAssert.assertThat(List.of(firstPage, secondPage), containsInAnyOrder(first, second));
    }

    @ParameterizedTest
    @CsvSource({"0,0,0,0,0,0", "0,1,1,1,1,0", "1,0,1,1,0,1", "1,1,0,0,1,1",
            "1,1,1,0,0,0", "1,0,0,1,1,0", "0,1,0,1,0,1", "0,0,1,0,1,1"})
    void shouldCombineCardFiltersPairwise(int status, int issuer, int start, int end, int page, int offset) {
        // Given: L8 покрывает все пары шести двоичных факторов, BIN фиксирует собственную выборку.
        String bin = ownBin();
        String pan = createCard(Data.changed(Data.card(), "bin", bin)).path("pan");
        String blocked = createCard(Data.changed(Data.card(), "bin", bin)).path("pan");
        gateway().body(Map.of("status", "BLOCKED")).patch("/api/cards/{pan}", blocked).then().statusCode(200)
                .body("status", equalTo("BLOCKED"));
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("bin", bin);
        filters.put("status", new String[]{"ACTIVE", "BLOCKED"}[status]);
        filters.put("limit", page + 1);
        filters.put("offset", offset);
        addOptional(filters, "issuerId", "ISS001", issuer);
        addOptional(filters, "startDate", "2000-01-01T00:00:00Z", start);
        addOptional(filters, "endDate", "2100-01-01T00:00:00Z", end);

        // When
        Response response = gateway().queryParams(filters).get("/api/cards");

        // Then
        response.then().statusCode(200).body("total", equalTo(1), "cards", hasSize(1 - offset));
        List<String> expected = List.of(new String[]{pan, blocked}[status]).stream().skip(offset).toList();
        response.then().body("cards.pan", equalTo(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"startDate", "endDate", "issuerId"})
    void shouldReturnEmptySelectionForNonMatchingFilter(String field) {
        // Given
        String bin = ownBin();
        Response card = createCard(Data.changed(Data.card(), "bin", bin));
        Instant created = Instant.parse(card.path("createdAt"));
        Map<String, String> values = Map.of("startDate", created.plusSeconds(1).toString(),
                "endDate", created.minusSeconds(1).toString(), "issuerId", "OTHER");

        // When
        Response response = gateway().queryParam("bin", bin).queryParam(field, values.get(field)).get("/api/cards");

        // Then
        response.then().statusCode(200).body("total", equalTo(0), "cards", hasSize(0));
    }

    @ParameterizedTest
    @CsvSource({"limit,0", "limit,-1", "offset,-1", "bin,40000", "bin,4000000", "bin,40000X",
            "issuerId,lower", "issuerId,ABCDEFGHIJK", "issuerId,''"})
    void shouldRejectInvalidCardFilter(String field, String value) {
        // Given / When
        Response response = gateway().queryParam(field, value).get("/api/cards");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"), "message", containsString(field));
    }

    @Test
    void shouldRejectPageAboveMaximum() {
        // Given / When
        Response response = gateway().queryParam("limit", 10001).get("/api/cards");

        // Then
        response.then().statusCode(413).body("serviceName", equalTo("card-management"),
                "error", equalTo("TooLargeLimitException"));
    }

    @Test
    void shouldReturnTotalButEmptyPagePastEnd() {
        // Given
        String bin = ownBin();
        createCard(Data.changed(Data.card(), "bin", bin));

        // When
        Response response = gateway().queryParam("bin", bin).queryParam("offset", 1).get("/api/cards");

        // Then
        response.then().statusCode(200).body("total", equalTo(1), "cards", hasSize(0));
    }

    private String ownBin() {
        return ownBin("ISS001");
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "ABCDEFGHIJ"})
    void shouldAcceptIssuerIdLengthBoundaries(String issuerId) {
        // Given
        String bin = ownBin(issuerId);
        String pan = createCard(Data.changed(Data.card(), "bin", bin)).path("pan");

        // When
        Response response = gateway().queryParam("bin", bin).queryParam("issuerId", issuerId).get("/api/cards");

        // Then
        response.then().statusCode(200).body("total", equalTo(1), "cards", hasSize(1),
                "cards[0].pan", equalTo(pan), "cards[0].issuerId", equalTo(issuerId));
    }

    private String ownBin(String issuerId) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String bin = "2" + String.format("%05d",
                    Math.floorMod(java.util.UUID.randomUUID().getLeastSignificantBits(), 100000));
            Response response = given().spec(Api.CARDS).body(Map.of("bin", bin, "issuerId", issuerId))
                    .post("/api/bins");
            if (response.statusCode() == 201) {
                response.then().body("bin", equalTo(bin));
                return bin;
            }
            response.then().statusCode(409).body("error", equalTo("BinAlreadyExistException"));
        }
        throw new AssertionError("Could not allocate a unique BIN");
    }

    private void addOptional(Map<String, Object> filters, String field, Object value, int enabled) {
        if (enabled == 1) {
            filters.put(field, value);
        }
    }
}
