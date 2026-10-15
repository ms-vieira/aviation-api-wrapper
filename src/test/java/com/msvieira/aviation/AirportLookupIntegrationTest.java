package com.msvieira.aviation;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.msvieira.aviation.adapter.web.CorrelationIdFilter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class AirportLookupIntegrationTest extends IntegrationTestSupport {

    @Test
    void returnsMappedAirportForKnownIcao() {
        stubAirport("KJFK", okJson(fixture()));

        Response res = call("/api/v1/airports/KJFK");

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.contentType()).hasToString(MediaType.APPLICATION_JSON_VALUE);
        JsonNode body = res.json();
        assertThat(body.path("icao").asString()).isEqualTo("KJFK");
        assertThat(body.path("iata").asString()).isEqualTo("JFK");
        assertThat(body.path("name").asString()).isEqualTo("NEW YORK/JOHN F KENNEDY INTL");
        assertThat(body.path("state").asString()).isEqualTo("NY");
        assertThat(body.path("country").asString()).isEqualTo("US");
        assertThat(body.path("latitude").asDouble()).isEqualTo(40.6399);
        assertThat(body.path("longitude").asDouble()).isEqualTo(-73.7787);
        assertThat(body.path("elevationMeters").asInt()).isEqualTo(4);
        assertThat(body.path("runways")).hasSize(4);
        assertThat(body.path("runways").get(0).path("id").asString()).isEqualTo("04L/22R");
        assertThat(body.path("source").asString()).isEqualTo("aviationweather");

        wireMock.verify(
                1,
                getRequestedFor(urlPathEqualTo(UPSTREAM_PATH))
                        .withQueryParam("ids", equalTo("KJFK"))
                        .withQueryParam("format", equalTo("json"))
                        .withHeader(HttpHeaders.USER_AGENT, equalTo("aviation-api-wrapper-test/1.0")));
    }

    @Test
    void lowercaseAndUppercaseCodesShareOneCacheEntry() {
        stubAirport("KJFK", okJson(fixture()));

        assertThat(call("/api/v1/airports/kjfk").status()).isEqualTo(200);
        assertThat(call("/api/v1/airports/KJFK").status()).isEqualTo(200);

        wireMock.verify(1, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void concurrentMissesForTheSameCodeCallUpstreamOnce() throws Exception {
        stubAirport("KJFK", okJson(fixture()).withFixedDelay(200));
        int clients = 20;

        List<Future<Response>> results;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Response>> lookups = Collections.nCopies(clients, () -> call("/api/v1/airports/KJFK"));
            results = pool.invokeAll(lookups);
        }

        for (Future<Response> result : results) {
            assertThat(result.get().status()).isEqualTo(200);
        }
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void concurrentCallsBeyondTheBulkheadAreRejectedWithoutQueueingOnUpstream() throws Exception {
        stubAnyAirport(okJson(fixtureForRequestedCode()).withFixedDelay(100));
        List<String> codes = List.of("KJFK", "KLAX", "KSFO", "KORD", "KBOS", "KSEA");
        Response warmUp = call("/api/v1/airports/KDEN");
        assertThat(warmUp.status()).as("warm-up: %s", warmUp.body()).isEqualTo(200);

        List<Future<Response>> results;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            results = pool.invokeAll(codes.stream()
                    .map(code -> (Callable<Response>) () -> call("/api/v1/airports/" + code))
                    .toList());
        }

        List<Response> responses = new ArrayList<>();
        for (Future<Response> result : results) {
            responses.add(result.get());
        }
        assertThat(responses)
                .as("bodies: %s", responses.stream().map(Response::body).toList())
                .extracting(Response::status)
                .containsOnly(200, 503)
                .contains(200, 503);
        List<Response> served =
                responses.stream().filter(r -> r.status() == 200).toList();
        List<Response> rejected =
                responses.stream().filter(r -> r.status() == 503).toList();
        rejected.forEach(r -> assertProblem(r, "upstream-saturated"));
        wireMock.verify(1 + served.size(), getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
        assertThat(circuitBreakerRegistry.circuitBreaker(CB_NAME).getMetrics().getNumberOfFailedCalls())
                .isZero();
    }

    @Test
    void echoesIncomingCorrelationIdAndGeneratesOneWhenAbsent() {
        stubAirport("KJFK", okJson(fixture()));

        Response withHeader = http.get()
                .uri("/api/v1/airports/KJFK")
                .header(CorrelationIdFilter.HEADER, "req-42")
                .exchange((req, res) -> Response.of(res));
        assertThat(withHeader.headers().getFirst(CorrelationIdFilter.HEADER)).isEqualTo("req-42");

        Response without = call("/api/v1/airports/KJFK");
        assertThat(without.headers().getFirst(CorrelationIdFilter.HEADER)).isNotBlank();
    }

    @Test
    void invalidIcaoIsRejectedWithoutCallingUpstream() {
        for (String bad : new String[] {"K1A", "1234", "KJFKK"}) {
            Response res = call("/api/v1/airports/" + bad);

            assertThat(res.status()).as(bad).isEqualTo(400);
            assertProblem(res, "invalid-icao-code");
        }
        wireMock.verify(0, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void upstreamNoContentBecomesNotFoundAndIsNotRetried() {
        stubAirport("ZZZZ", aResponse().withStatus(204));

        Response res = call("/api/v1/airports/ZZZZ");

        assertThat(res.status()).isEqualTo(404);
        assertProblem(res, "airport-not-found");
        assertThat(res.json().path("detail").asString()).contains("ZZZZ");
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void retriesTransientServerErrorsAndSucceeds() {
        wireMock.stubFor(get(urlPathEqualTo(UPSTREAM_PATH))
                .inScenario("flaky")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("second"));
        wireMock.stubFor(get(urlPathEqualTo(UPSTREAM_PATH))
                .inScenario("flaky")
                .whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("third"));
        wireMock.stubFor(get(urlPathEqualTo(UPSTREAM_PATH))
                .inScenario("flaky")
                .whenScenarioStateIs("third")
                .willReturn(okJson(fixture())));

        Response res = call("/api/v1/airports/KJFK");

        assertThat(res.status()).isEqualTo(200);
        wireMock.verify(3, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void exhaustedRetriesOnServerErrorBecomeServiceUnavailable() {
        stubAirport("KJFK", aResponse().withStatus(502));

        Response res = call("/api/v1/airports/KJFK");

        assertThat(res.status()).isEqualTo(503);
        assertProblem(res, "upstream-unavailable");
        wireMock.verify(3, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void upstreamTimeoutBecomesGatewayTimeoutAfterRetries() {
        stubAirport("KJFK", okJson(fixture()).withFixedDelay(1_000));

        Response res = call("/api/v1/airports/KJFK");

        assertThat(res.status()).isEqualTo(504);
        assertProblem(res, "upstream-timeout");
        wireMock.verify(3, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void upstreamRateLimitIsNotRetriedAndRetryAfterIsForwarded() {
        stubAirport("KJFK", aResponse().withStatus(429).withHeader(HttpHeaders.RETRY_AFTER, "30"));

        Response res = call("/api/v1/airports/KJFK");

        assertThat(res.status()).isEqualTo(503);
        assertProblem(res, "upstream-rate-limited");
        assertThat(res.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void outboundBudgetRejectsFurtherDistinctLookupsWithoutCallingUpstream() {
        limitOutboundCalls(2);
        stubAnyAirport(okJson(fixtureForRequestedCode()));

        assertThat(call("/api/v1/airports/KJFK").status()).isEqualTo(200);
        assertThat(call("/api/v1/airports/KLAX").status()).isEqualTo(200);

        Response rejected = call("/api/v1/airports/KSFO");
        assertThat(rejected.status()).isEqualTo(503);
        assertProblem(rejected, "outbound-rate-limited");
        assertThat(rejected.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(call("/api/v1/airports/KORD").status()).isEqualTo(503);

        wireMock.verify(2, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
        assertThat(circuitBreakerRegistry.circuitBreaker(CB_NAME).getMetrics().getNumberOfFailedCalls())
                .isZero();
    }

    @Test
    void refreshGrantsOnlyTheNextSmallQuota() throws InterruptedException {
        limitOutboundCalls(2, Duration.ofSeconds(2));
        stubAnyAirport(okJson(fixtureForRequestedCode()));

        assertThat(call("/api/v1/airports/KJFK").status()).isEqualTo(200);
        assertThat(call("/api/v1/airports/KLAX").status()).isEqualTo(200);
        Response insideWindow = call("/api/v1/airports/KSFO");
        assertThat(insideWindow.status()).isEqualTo(503);
        assertProblem(insideWindow, "outbound-rate-limited");
        assertThat(insideWindow.headers().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");

        Thread.sleep(300);
        assertThat(call("/api/v1/airports/KORD").status()).isEqualTo(503);
        wireMock.verify(2, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));

        Response reopened = awaitStatus("/api/v1/airports/KBOS", 200, Duration.ofSeconds(4));
        assertThat(reopened.status()).isEqualTo(200);
        assertThat(call("/api/v1/airports/KSEA").status()).isEqualTo(200);
        Response closedAgain = call("/api/v1/airports/KDEN");
        assertThat(closedAgain.status()).isEqualTo(503);
        assertProblem(closedAgain, "outbound-rate-limited");
        wireMock.verify(4, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void eachRetryAttemptConsumesOneOutboundPermit() {
        limitOutboundCalls(3);
        stubAnyAirport(aResponse().withStatus(500));

        Response exhausted = call("/api/v1/airports/KJFK");
        assertThat(exhausted.status()).isEqualTo(503);
        assertProblem(exhausted, "upstream-unavailable");
        wireMock.verify(3, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));

        Response blocked = call("/api/v1/airports/KLAX");
        assertThat(blocked.status()).isEqualTo(503);
        assertProblem(blocked, "outbound-rate-limited");
        wireMock.verify(3, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void unexpectedClientErrorFromUpstreamBecomesBadGatewayWithoutRetry() {
        stubAirport("KJFK", aResponse().withStatus(403).withBody("forbidden"));

        Response res = call("/api/v1/airports/KJFK");

        assertThat(res.status()).isEqualTo(502);
        assertProblem(res, "upstream-bad-response");
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void malformedUpstreamBodyBecomesBadGateway() {
        stubAirport(
                "KJFK",
                aResponse()
                        .withStatus(200)
                        .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .withBody("<html>maintenance</html>"));

        Response res = call("/api/v1/airports/KJFK");

        assertThat(res.status()).isEqualTo(502);
        assertProblem(res, "upstream-bad-response");
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableRecords")
    void unusableRecordsBecomeBadGatewayWithoutRetry(String label, String body) {
        stubAirport("KJFK", okJson(body));

        Response res = call("/api/v1/airports/KJFK");

        assertThat(res.status()).as(res.body()).isEqualTo(502);
        assertProblem(res, "upstream-bad-response");
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    static Stream<Arguments> unusableRecords() {
        return Stream.of(
                Arguments.of(
                        "record for another airport",
                        "[{\"icaoId\":\"KLAX\",\"name\":\"LOS ANGELES\",\"lat\":33.94,\"lon\":-118.4}]"),
                Arguments.of("record without identifier", "[{\"name\":\"X\",\"lat\":40.6,\"lon\":-73.7}]"),
                Arguments.of("null element", "[null]"),
                Arguments.of("blank name", "[{\"icaoId\":\"KJFK\",\"name\":\"  \",\"lat\":40.6,\"lon\":-73.7}]"),
                Arguments.of(
                        "impossible coordinates",
                        "[{\"icaoId\":\"KJFK\",\"name\":\"X\",\"lat\":999.0,\"lon\":-999.0}]"));
    }

    @Test
    void unusableRecordIsNotCachedSoTheNextRequestAsksUpstreamAgain() {
        stubAirport("KJFK", okJson("[{\"icaoId\":\"KLAX\",\"name\":\"LOS ANGELES\",\"lat\":33.94,\"lon\":-118.4}]"));
        assertThat(call("/api/v1/airports/KJFK").status()).isEqualTo(502);

        wireMock.resetAll();
        stubAirport("KJFK", okJson(fixture()));

        Response res = call("/api/v1/airports/KJFK");

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.json().path("name").asString()).isEqualTo("NEW YORK/JOHN F KENNEDY INTL");
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void failedLookupsAreNotCached() {
        stubAirport("KJFK", aResponse().withStatus(500));
        assertThat(call("/api/v1/airports/KJFK").status()).isEqualTo(503);
        wireMock.verify(3, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));

        wireMock.resetAll();
        stubAirport("KJFK", okJson(fixture()));

        assertThat(call("/api/v1/airports/KJFK").status()).isEqualTo(200);
        assertThat(call("/api/v1/airports/KJFK").status()).isEqualTo(200);
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndShortCircuitsSubsequentCalls() {
        stubAirport("KJFK", aResponse().withStatus(500));
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(CB_NAME);

        Response first = call("/api/v1/airports/KJFK");
        assertThat(first.status()).isEqualTo(503);
        assertProblem(first, "upstream-unavailable");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        Response second = call("/api/v1/airports/KJFK");
        assertThat(second.status()).isEqualTo(503);
        assertProblem(second, "upstream-circuit-open");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        wireMock.verify(5, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));

        Response third = call("/api/v1/airports/KJFK");
        assertThat(third.status()).isEqualTo(503);
        assertProblem(third, "upstream-circuit-open");
        wireMock.verify(5, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void circuitClosesAgainOnceUpstreamRecovers() {
        stubAnyAirport(aResponse().withStatus(500));
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(CB_NAME);
        call("/api/v1/airports/KJFK");
        call("/api/v1/airports/KJFK");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        wireMock.resetAll();
        stubAnyAirport(okJson(fixtureForRequestedCode()));

        Response afterWait = awaitStatus("/api/v1/airports/KLAX", 200, Duration.ofSeconds(4));
        assertThat(afterWait.status()).isEqualTo(200);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);

        assertThat(call("/api/v1/airports/KSFO").status()).isEqualTo(200);
        assertThat(call("/api/v1/airports/KORD").status()).isEqualTo(200);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void notFoundDoesNotCountTowardsCircuitBreakerFailures() {
        stubAirport("ZZZZ", aResponse().withStatus(204));
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker(CB_NAME);

        for (int i = 0; i < 6; i++) {
            assertThat(call("/api/v1/airports/ZZZZ").status()).isEqualTo(404);
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void unknownRouteIsNotFoundProblemNotInternalError() {
        Response res = call("/api/v1/flights/KJFK");

        assertThat(res.status()).isEqualTo(404);
        assertProblem(res, "resource-not-found");
        assertThat(res.json().path("detail").asString()).isEqualTo("No endpoint for GET /api/v1/flights/KJFK");
        wireMock.verify(0, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void unsupportedMethodIsMethodNotAllowedProblem() {
        Response res = http.post().uri("/api/v1/airports/KJFK").exchange((req, r) -> Response.of(r));

        assertThat(res.status()).isEqualTo(405);
        assertProblem(res, "method-not-allowed");
        assertThat(res.headers().getAllow()).containsExactly(HttpMethod.GET);
        wireMock.verify(0, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }

    @Test
    void unacceptableMediaTypeIsNotAcceptableProblem() {
        stubAirport("KJFK", okJson(fixture()));

        Response res = http.get()
                .uri("/api/v1/airports/KJFK")
                .accept(MediaType.APPLICATION_XML)
                .exchange((req, r) -> Response.of(r));

        assertThat(res.status()).isEqualTo(406);
        assertProblem(res, "not-acceptable");
    }

    private Response awaitStatus(String path, int expected, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        Response last;
        do {
            last = call(path);
            if (last.status() == expected) {
                return last;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        } while (Instant.now().isBefore(deadline));
        return last;
    }
}
