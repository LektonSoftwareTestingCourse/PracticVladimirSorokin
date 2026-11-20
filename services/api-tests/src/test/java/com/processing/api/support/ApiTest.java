package com.processing.api.support;

import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;

import java.time.Duration;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

public abstract class ApiTest {
    protected Response createCard() {
        return createCard(Data.card());
    }

    protected Response createCard(Map<String, Object> body) {
        return Api.gateway().body(body).post("/api/cards").then().statusCode(201).extract().response();
    }

    protected void balance(String pan, int expected) {
        // Прямой GET CMS обходит кэш Gateway.
        given().spec(Api.CARDS).get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("availableBalance", equalTo(expected));
    }

    protected void awaitLogged(String pan, String stan) {
        // 10 секунд — бюджет доставки в локальном стенде, опрос раз в 100 мс.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                Api.gateway().queryParam("pan", pan).get("/api/transactions/search").then().statusCode(200)
                        .body("transactions.findAll { it.stan == '" + stan + "' }.size()", equalTo(1)));
    }

    @AfterEach
    void clearFaultRules() {
        given().spec(Api.FAULTS).delete("/__rules").then().statusCode(200);
    }
}
