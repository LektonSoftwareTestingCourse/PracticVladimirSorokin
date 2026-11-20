package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.ApiTest;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.processing.api.support.Api.gateway;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

// Источники: TransactionFilter, TransactionSpecification, ChartsFilter, DashboardController, CardController.
class SearchApiTest extends ApiTest {
    @ParameterizedTest
    @CsvSource({"status,APPROVED,APPROVED", "merchantId,MERCH0000000001,APPROVED", "issuerId,ISS001,APPROVED",
            "mcc,5411,APPROVED", "declineReason,blocked,DECLINED", "dateFrom,TOMORROW,DECLINED", "dateTo,TODAY,APPROVED"})
    void shouldApplyEachSearchFilterSeparately(String field, String value, String expectedStatus) {
        // Given
        String pan = Data.missingPan();
        storeFixtures(pan);
        Map<String, String> dates = Map.of("TODAY", LocalDate.now(ZoneOffset.UTC).toString(),
                "TOMORROW", LocalDate.now(ZoneOffset.UTC).plusDays(1).toString());

        // When
        Response response = gateway().queryParam("pan", pan).queryParam(field, dates.getOrDefault(value, value))
                .get("/api/transactions/search");

        // Then
        response.then().statusCode(200).body("total", equalTo(1), "transactions", hasSize(1),
                "transactions[0].status", equalTo(expectedStatus), "transactions[0].pan", equalTo(pan));
    }

    @ParameterizedTest
    @MethodSource("searchPairs")
    void shouldCombineSearchFilters(Map<String, String> filters, List<String> expectedStatuses, int total) {
        // Given: две записи на собственном PAN; старшая по createdAt — DECLINED.
        String pan = Data.missingPan();
        storeFixtures(pan);

        // When
        Response response = gateway().queryParam("pan", pan).queryParams(filters).get("/api/transactions/search");

        // Then
        response.then().statusCode(200).body("total", equalTo(total), "transactions", hasSize(expectedStatuses.size()),
                "transactions.status", equalTo(expectedStatuses));
    }

    @ParameterizedTest
    @CsvSource({"limit,0", "limit,-1", "limit,501", "offset,-1", "status,LOST", "dateFrom,yesterday",
            "dateTo,2026-99-99"})
    void shouldRejectInvalidSearchParameter(String field, String value) {
        // Given / When
        Response response = gateway().queryParam(field, value).get("/api/transactions/search");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"),
                "message", containsString(field));
    }

    @Test
    void shouldRejectReversedSearchDates() {
        // Given / When
        Response response = gateway().queryParam("dateFrom", "2026-11-21").queryParam("dateTo", "2026-11-20")
                .get("/api/transactions/search");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"), "message", notNullValue());
    }

    @Test
    void shouldReturnEmptyPageForUnknownPan() {
        // Given / When
        Response response = gateway().queryParam("pan", Data.missingPan()).get("/api/transactions/search");

        // Then
        response.then().statusCode(200).body("total", equalTo(0), "transactions", hasSize(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 500})
    void shouldAcceptSearchPageBoundaries(int limit) {
        // Given
        String pan = Data.missingPan();
        storeFixtures(pan);

        // When
        Response response = gateway().queryParam("pan", pan).queryParam("limit", limit)
                .queryParam("offset", 0).get("/api/transactions/search");

        // Then
        response.then().statusCode(200).body("total", equalTo(2), "transactions", hasSize(Math.min(limit, 2)));
    }

    @Test
    void shouldExportOnlyOwnTransactionsAsCsv() {
        // Given
        String pan = Data.missingPan();
        storeFixtures(pan);
        given().spec(Api.LOGGER).body(Data.transaction(Data.missingPan())).post("/api/internal/log")
                .then().statusCode(201).body("status", equalTo("stored"));

        // When
        Response response = gateway().accept("text/csv").queryParam("pan", pan).get("/api/transactions/export");

        // Then
        response.then().statusCode(200).contentType(containsString("text/csv"))
                .header("Content-Disposition", equalTo("attachment; filename=\"transactions.csv\""))
                .body(containsString(pan), containsString("APPROVED"), containsString("DECLINED"));
        List<String> rows = response.asString().lines().toList();
        org.junit.jupiter.api.Assertions.assertEquals(3, rows.size());
        org.junit.jupiter.api.Assertions.assertTrue(rows.getFirst().startsWith("id,mti,stan,rrn,pan,"));
        org.hamcrest.MatcherAssert.assertThat(rows.subList(1, rows.size()),
                org.hamcrest.Matchers.everyItem(containsString("," + pan + ",")));
    }

    @Test
    void shouldValidateExportFilter() {
        // Given / When
        Response response = gateway().queryParam("limit", 0).get("/api/transactions/export");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"), "message", containsString("limit"));
    }

    @Test
    void shouldExposeDashboardCounters() {
        // Given / When
        Response response = gateway().get("/api/dashboard/stats");

        // Then
        response.then().statusCode(200).body("totalTransactions", greaterThanOrEqualTo(0),
                "approvedCount", greaterThanOrEqualTo(0), "declinedCount", greaterThanOrEqualTo(0),
                "approvalRate", notNullValue(), "totalAmount", notNullValue(), "averageAmount", notNullValue());
        long total = response.jsonPath().getLong("totalTransactions");
        org.junit.jupiter.api.Assertions.assertEquals(total,
                response.jsonPath().getLong("approvedCount") + response.jsonPath().getLong("declinedCount"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 500})
    void shouldRespectRecentLimit(int limit) {
        // Given
        String pan = Data.missingPan();
        List<String> expectedIds = storeRecentFixtures(pan).stream().limit(limit).toList();

        // When
        Response response = gateway().queryParam("limit", limit).get("/api/dashboard/recent");

        // Then
        response.then().statusCode(200).body("size()", org.hamcrest.Matchers.lessThanOrEqualTo(limit),
                "size()", greaterThanOrEqualTo(expectedIds.size()),
                "id.take(" + expectedIds.size() + ")", equalTo(expectedIds));
        List<Instant> created = response.jsonPath().getList("createdAt", String.class).stream().map(Instant::parse).toList();
        org.junit.jupiter.api.Assertions.assertEquals(created.stream().sorted(java.util.Comparator.reverseOrder()).toList(),
                created);
    }

    private List<String> storeRecentFixtures(String pan) {
        List<String> latest = gateway().queryParam("limit", 1).get("/api/dashboard/recent").then().statusCode(200)
                .extract().jsonPath().getList("createdAt", String.class);
        Instant time = latest.stream().map(Instant::parse).max(java.util.Comparator.naturalOrder())
                .orElseGet(Instant::now).plusSeconds(1);
        Map<String, Object> first = Data.changed(Data.transaction(pan), "createdAt", time.toString());
        Map<String, Object> second = Data.changed(Data.transaction(pan), "createdAt", time.plusSeconds(1).toString());
        given().spec(Api.LOGGER).body(first).post("/api/internal/log").then().statusCode(201)
                .body("id", equalTo(first.get("id")));
        given().spec(Api.LOGGER).body(second).post("/api/internal/log").then().statusCode(201)
                .body("id", equalTo(second.get("id")));
        return List.of(second.get("id").toString(), first.get("id").toString());
    }

    @Test
    void shouldRejectReversedChartDates() {
        // Given / When
        Response response = gateway().queryParam("from", "2026-11-21T00:00:00Z")
                .queryParam("to", "2026-11-20T00:00:00Z").get("/api/dashboard/charts");

        // Then: ChartsFilter / ValidDateRange; пустое сообщение обработчика зафиксировано в D-04.
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"),
                "error", equalTo("Validation error"), "message", equalTo(""), "retryAfterMs", equalTo("0"));
    }

    @Test
    void shouldReturnEmptyChartForEqualDates() {
        // Given / When: диапазон [from,to) пуст при равных границах.
        Response response = gateway().queryParam("from", "2026-11-20T00:00:00Z")
                .queryParam("to", "2026-11-20T00:00:00Z").get("/api/dashboard/charts");

        // Then
        response.then().statusCode(200).body("size()", equalTo(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 501})
    void shouldValidateRecentLimit(int limit) {
        // Given / When
        Response response = gateway().queryParam("limit", limit).get("/api/dashboard/recent");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"), "message", containsString("limit"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hour", "day"})
    void shouldReadChartBuckets(String granularity) {
        // Given
        Instant from = emptyChartDay();
        Instant to = from.plus(1, ChronoUnit.DAYS);
        String pan = Data.missingPan();
        storeAt(pan, from.plus(1, ChronoUnit.HOURS));
        storeAt(pan, from.plus(2, ChronoUnit.HOURS));
        storeAt(pan, to);

        // When
        Response response = gateway().queryParam("granularity", granularity).queryParam("from", from.toString())
                .queryParam("to", to.toString()).get("/api/dashboard/charts");

        // Then
        List<Integer> counts = Map.of("hour", List.of(1, 1), "day", List.of(2)).get(granularity);
        List<String> times = Map.of("hour", List.of(from.plus(1, ChronoUnit.HOURS).toString(),
                from.plus(2, ChronoUnit.HOURS).toString()), "day", List.of(from.toString())).get(granularity);
        response.then().statusCode(200).body("total", equalTo(counts), "approved", equalTo(counts),
                "timestamp", equalTo(times), "declined", org.hamcrest.Matchers.everyItem(equalTo(0)));
    }

    private Instant emptyChartDay() {
        for (int attempt = 0; attempt < 5; attempt++) {
            long offset = Math.floorMod(java.util.UUID.randomUUID().getLeastSignificantBits(), 200000);
            Instant start = Instant.parse("2030-01-01T00:00:00Z").plus(offset, ChronoUnit.DAYS);
            Response response = gateway().queryParam("from", start.toString())
                    .queryParam("to", start.plus(1, ChronoUnit.DAYS).toString()).queryParam("granularity", "day")
                    .get("/api/dashboard/charts").then().statusCode(200).extract().response();
            if (response.jsonPath().getList("").isEmpty()) {
                return start;
            }
        }
        throw new AssertionError("Could not find an empty chart window");
    }

    private void storeAt(String pan, Instant created) {
        given().spec(Api.LOGGER).body(Data.changed(Data.transaction(pan), "createdAt", created.toString()))
                .post("/api/internal/log").then().statusCode(201).body("status", equalTo("stored"));
    }

    @ParameterizedTest
    @CsvSource({"granularity,minute", "from,not-a-date", "to,not-a-date"})
    void shouldRejectInvalidChartParameter(String field, String value) {
        // Given / When
        Response response = gateway().queryParam(field, value).get("/api/dashboard/charts");

        // Then
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"), "message", containsString(field));
    }

    private void storeFixtures(String pan) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<String, Object> first = Data.transaction(pan);
        first.put("transmissionDateTime", today.atStartOfDay(ZoneOffset.UTC).toInstant().toString());
        first.put("createdAt", "2026-01-01T00:00:00Z");
        Map<String, Object> second = Data.transaction(pan);
        second.putAll(Map.of("status", "DECLINED", "merchantId", "MERCH0000000002", "issuerId", "ISS002", "mcc", "5812",
                "declineReason", "card blocked", "createdAt", "2026-01-02T00:00:00Z",
                "transmissionDateTime", today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString()));
        given().spec(Api.LOGGER).body(first).post("/api/internal/log").then().statusCode(201).body("status", equalTo("stored"));
        given().spec(Api.LOGGER).body(second).post("/api/internal/log").then().statusCode(201).body("status", equalTo("stored"));
    }

    static Stream<Arguments> searchPairs() {
        return IntStream.range(0, 16).mapToObj(SearchApiTest::searchRow);
    }

    private static Arguments searchRow(int row) {
        // L16: попарное покрытие девяти двоичных факторов. PAN всегда ограничивает выборку своим тестом.
        String[] names = {"status", "merchantId", "issuerId", "mcc", "declineReason", "dateFrom", "dateTo"};
        String today = LocalDate.now(ZoneOffset.UTC).toString();
        String tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString();
        String[] values = {"APPROVED", "MERCH0000000001", "ISS001", "5411", "blocked", today, today};
        Map<String, String> filters = new LinkedHashMap<>();
        boolean first = true;
        boolean second = true;
        for (int factor = 1; factor <= 7; factor++) {
            if ((Integer.bitCount(row & factor) & 1) == 1) {
                filters.put(names[factor - 1], values[factor - 1]);
                if (factor == 5) {
                    first = false;
                } else if (factor != 6) {
                    second = false;
                }
            }
        }
        int limit = 1 + (Integer.bitCount(row & 8) & 1);
        int offset = Integer.bitCount(row & 9) & 1;
        filters.put("limit", String.valueOf(limit));
        filters.put("offset", String.valueOf(offset));
        List<String> expected = new ArrayList<>();
        if (second) {
            expected.add("DECLINED");
        }
        if (first) {
            expected.add("APPROVED");
        }
        int total = expected.size();
        return Arguments.of(filters, expected.stream().skip(offset).limit(limit).toList(), total);
    }
}
