package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.Map;

import static com.processing.api.support.Api.gateway;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

// Проверки исходного контракта. Запускаются отдельно; красные результаты сохраняются в отчёт, не скрываются @Disabled.
@Tag("contract-defect")
class ContractDefectsTest extends ApiTest {
    @Test
    void shouldIdentifyInvalidGenerationField() {
        // Given / When: условие практики требует имя нарушенного поля в теле ошибки.
        Response response = gateway().body(Map.of("count", 1)).post("/api/cards/generate");

        // Then
        response.then().statusCode(400).body("message", containsString("bins"));
    }

    @ParameterizedTest
    @CsvSource({"status,UNKNOWN", "startDate,yesterday", "endDate,yesterday"})
    void shouldRejectMalformedCardFilterWith400(String field, String value) {
        // Given / When: невалидные query-параметры CardController должны давать 400.
        Response response = given().spec(Api.CARDS).queryParam(field, value).get("/api/cards");

        // Then
        response.then().statusCode(400);
    }

    @ParameterizedTest
    @CsvSource({"currencyCode,ABC", "terminalType,UNKNOWN", "stan,ABCDEF", "processingCode,ABCDEF"})
    void shouldRejectDocumentedFieldFormatViolation(String field, String value) {
        // Given: числовые коды и перечисление terminalType из docs/api-spec.md и tz/02-gateway.md.
        String pan = createCard().path("pan");

        // When
        Response response = gateway().body(Data.changed(Data.payment(pan), field, value)).post("/api/transactions");

        // Then
        response.then().statusCode(400).body("message", containsString(field));
    }

    @Test
    void shouldReturnResponseMti0110() {
        // Given: docs/api-spec.md, AuthorizationResponse.mti = 0110.
        String pan = createCard().path("pan");

        // When
        Response response = gateway().body(Data.payment(pan)).post("/api/transactions");

        // Then
        response.then().statusCode(200).body("mti", equalTo("0110"));
    }

    @Test
    void shouldReturnExpiryInDocumentedMmYyFormat() {
        // Given / When: docs/api-spec.md, Card.expiryDate = MMYY.
        Response response = given().spec(Api.CARDS).body(Data.card()).post("/api/cards");

        // Then
        response.then().statusCode(201).body("expiryDate", matchesPattern("(0[1-9]|1[0-2])\\d{2}"));
    }

    @Test
    void shouldReturn400ForMalformedCardJson() {
        // Given / When: CardController — ошибки входных данных 400.
        Response response = given().spec(Api.CARDS).body("{").post("/api/cards");

        // Then
        response.then().statusCode(400);
    }

    @Test
    void shouldRejectMissingReserveAmountAsInputError() {
        // Given: обязательная сумма резерва в tz/05-card-management.md.
        String pan = createCard().path("pan");

        // When
        Response response = given().spec(Api.CARDS).body(Map.of("rrn", Data.rrn()))
                .post("/api/cards/{pan}/reserve", pan);

        // Then
        response.then().statusCode(400);
    }

    @Test
    void shouldNotRefundMoreThanReserved() {
        // Given: возврат по резерву не должен создавать дополнительный рубль/копейку, tz/05, rollback.
        String pan = createCard().path("pan");
        String rrn = Data.rrn();
        gateway().body(Map.of("amount", 100, "rrn", rrn)).post("/api/cards/{pan}/reserve", pan)
                .then().statusCode(200).body("availableBalance", equalTo(900));

        // When
        Response response = gateway().body(Map.of("pan", pan, "amount", 101, "rrn", rrn))
                .post("/api/cards/{pan}/rollback", pan);

        // Then
        response.then().statusCode(400);
        balance(pan, 900);
    }

    @Test
    void shouldNameInvalidAuthorizationField() {
        // Given: требование практики 5 — ошибка должна указывать нарушенное поле и правило.
        Map<String, Object> request = Data.changed(Data.payment(Data.missingPan()), "amount", -1);

        // When
        Response response = given().spec(Api.AUTH).body(request).post("/api/internal/authorize");

        // Then
        response.then().statusCode(400).body("message", containsString("amount"));
    }

    @Test
    void shouldRejectZeroAtAuthorizationBoundary() {
        // Given: tz/04, положительная сумма; Gateway также требует amount > 0.
        String pan = createCard().path("pan");

        // When
        Response response = given().spec(Api.AUTH).body(Data.changed(Data.payment(pan), "amount", 0))
                .post("/api/internal/authorize");

        // Then
        response.then().statusCode(400);
    }

    @Test
    void shouldKeepIdenticalNanosecondMessageIdempotent() {
        // Given: InternalTransactionController — повтор с теми же данными 200; ISO 8601 поддерживает доли секунды.
        Map<String, Object> message = Data.changed(Data.transaction(Data.missingPan()),
                "transmissionDateTime", "2026-11-19T12:00:00.123456789Z");
        given().spec(Api.LOGGER).body(message).post("/api/internal/log").then().statusCode(201)
                .body("id", equalTo(message.get("id")));

        // When
        Response response = given().spec(Api.LOGGER).body(message).post("/api/internal/log");

        // Then
        response.then().statusCode(200).body("id", equalTo(message.get("id")));
    }

    @Test
    void shouldRoutePublicMerchantListThroughGateway() {
        // Given / When: публичный SimulationController.merchants, остальные simulator endpoints доступны через Gateway.
        Response response = gateway().get("/api/simulator/merchants");

        // Then
        response.then().statusCode(200).body("[0].id", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    void shouldReturnDbUnavailableOutcomeInsteadOfUnhandledRollbackError() {
        // Given: AuthServiceImpl должен преобразовать ошибку записи usage в DB_UNAVAILABLE.
        Response card = createCard();
        String pan = card.path("pan");
        Map<String, Object> body = card.jsonPath().getMap("");
        BigDecimal huge = new BigDecimal("1E+50");
        body.putAll(Map.of("dailyLimit", huge, "monthlyLimit", huge, "availableBalance", huge));
        FaultApiTest.stub("GET", "/cms/api/cards/" + pan, 200, body);

        // When
        Response response = given().spec(Api.AUTH).body(Data.changed(Data.payment(pan), "amount", huge))
                .post("/api/internal/authorize");

        // Then
        response.then().statusCode(400).body("responseCode", equalTo("96"), "declineReason", equalTo("DB_UNAVAILABLE"));
    }
}
