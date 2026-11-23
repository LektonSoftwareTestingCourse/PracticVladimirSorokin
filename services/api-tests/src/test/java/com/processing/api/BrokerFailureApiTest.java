package com.processing.api;

import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static com.processing.api.support.Api.gateway;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

// Отказ инфраструктуры готовит run-api-tests.ps1; сами проверки обращаются только к HTTP API.
@Tag("broker-fault")
class BrokerFailureApiTest extends ApiTest {
    @Test
    void shouldRollbackApprovedPaymentWhenPublishingFails() {
        // Given: новая карта, брокер остановлен перед этим прогоном.
        String pan = Objects.requireNonNull(System.getProperty("probe.pan"));

        // When
        Response response = gateway().body(Data.payment(pan)).post("/api/transactions");

        // Then: tz/03-switch.md, отказ публикации после APPROVED вызывает rollback и ответ 96.
        AuthorizationApiTest.decline(response, 200, "96", "System error");
        balance(pan, 1000);
    }

    @Test
    void shouldKeepFailedPaymentAbsentAfterRecovery() {
        // Given: тот же PAN, брокер уже восстановлен скриптом.
        String pan = Objects.requireNonNull(System.getProperty("probe.pan"));
        AtomicReference<Map<String, Object>> approvedCanary = new AtomicReference<>();
        // Запущенный RabbitMQ ещё не гарантирует готовность соединений Java-клиентов.
        // Каждая попытка использует новую карту, чтобы не повторять один финансовый запрос.
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofSeconds(1)).untilAsserted(() -> {
            String canaryPan = createCard().path("pan");
            Map<String, Object> canary = Data.payment(canaryPan);
            gateway().body(canary).post("/api/transactions").then().statusCode(200)
                    .body("status", equalTo("APPROVED"), "responseCode", equalTo("00"));
            approvedCanary.set(canary);
        });
        Map<String, Object> canary = approvedCanary.get();
        awaitLogged((String) canary.get("pan"), (String) canary.get("stan"));

        // When / Then: всё десятисекундное окно поиска должно оставаться пустым.
        await().during(Duration.ofSeconds(10)).atMost(Duration.ofSeconds(12)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> gateway().queryParam("pan", pan).get("/api/transactions/search")
                        .then().statusCode(200).body("total", equalTo(0), "transactions", hasSize(0)));
        balance(pan, 1000);
    }
}
