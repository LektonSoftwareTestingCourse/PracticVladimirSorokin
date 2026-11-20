package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

// Источник: BinLookupController, внутренний HTTP-вызов из Authorization.
class BinLookupApiTest extends ApiTest {
    @ParameterizedTest
    @CsvSource({"400000,ISS001", "400001,ISS002", "400002,ISS003", "400003,ISS004", "400004,ISS005"})
    void shouldReturnConfiguredIssuer(String bin, String issuer) {
        // Given / When
        Response response = given().spec(Api.BINS).get("/api/bin/{bin}", bin);

        // Then
        response.then().statusCode(200).body("bin", equalTo(bin), "issuerId", equalTo(issuer),
                "issuerName", org.hamcrest.Matchers.notNullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"999999", "40000", "40000X"})
    void shouldReturnEmpty404ForUnknownBin(String bin) {
        // Given / When
        Response response = given().spec(Api.BINS).get("/api/bin/{bin}", bin);

        // Then
        response.then().statusCode(404).body(equalTo(""));
    }

    @Test
    void shouldApplyExplicitFailureFlag() {
        // Given / When
        Response response = given().spec(Api.BINS).queryParam("fail", true).get("/api/bin/400000");

        // Then
        response.then().statusCode(500).body(equalTo(""));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void shouldAcceptDelayParameter(int delay) {
        // Given / When
        Response response = given().spec(Api.BINS).queryParam("delay", delay).queryParam("fail", false)
                .get("/api/bin/400000");

        // Then
        response.then().statusCode(200).body("bin", equalTo("400000"), "issuerId", equalTo("ISS001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"delay", "fail"})
    void shouldRejectWrongParameterType(String field) {
        // Given / When
        Response response = given().spec(Api.BINS).queryParam(field, "wrong").get("/api/bin/400000");

        // Then
        response.then().statusCode(400).body("status", equalTo(400), "error", equalTo("Bad Request"),
                "path", equalTo("/api/bin/400000"));
    }
}
