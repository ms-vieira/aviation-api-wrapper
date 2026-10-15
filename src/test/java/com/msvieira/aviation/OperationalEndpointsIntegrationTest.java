package com.msvieira.aviation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;

class OperationalEndpointsIntegrationTest extends IntegrationTestSupport {

    @Test
    void healthExposesProbesAndCircuitBreakerState() {
        Response health = call("/actuator/health");

        assertThat(health.status()).isEqualTo(200);
        JsonNode body = health.json();
        assertThat(body.path("status").asString()).isEqualTo("UP");
        assertThat(body.path("components").path("livenessState").path("status").asString())
                .isEqualTo("UP");
        assertThat(body.path("components").path("readinessState").path("status").asString())
                .isEqualTo("UP");
        assertThat(body.path("components")
                        .path("circuitBreakers")
                        .path("details")
                        .path(CB_NAME)
                        .path("details")
                        .path("state")
                        .asString())
                .isEqualTo("CLOSED");
        assertThat(call("/actuator/health/liveness").status()).isEqualTo(200);
        assertThat(call("/actuator/health/readiness").status()).isEqualTo(200);
    }

    @Test
    void healthStaysUpWhileCircuitIsOpenButReportsIt() {
        stubAnyAirport(aResponse().withStatus(500));
        call("/api/v1/airports/KJFK");
        call("/api/v1/airports/KJFK");

        Response health = call("/actuator/health");

        assertThat(health.status()).isEqualTo(200);
        JsonNode breaker = health.json()
                .path("components")
                .path("circuitBreakers")
                .path("details")
                .path(CB_NAME);
        assertThat(breaker.path("status").asString()).isEqualTo("CIRCUIT_OPEN");
        assertThat(breaker.path("details").path("state").asString()).isEqualTo("OPEN");
    }

    @Test
    void prometheusExposesCacheResilienceAndHttpClientMetrics() {
        stubAirport("KJFK", okJson(fixture()));
        call("/api/v1/airports/KJFK");
        call("/api/v1/airports/KJFK");

        Response metrics = call("/actuator/prometheus");

        assertThat(metrics.status()).isEqualTo(200);
        assertThat(metrics.body())
                .contains("cache_gets_total{")
                .contains("resilience4j_circuitbreaker_state{")
                .contains("resilience4j_retry_calls_total{")
                .contains("http_client_requests_seconds_count{")
                .contains("http_server_requests_seconds_count{");
    }

    @Test
    void openApiDocumentCarriesContractFromInterfaceAndSharedProblemResponses() {
        Response docs = call("/v3/api-docs");

        assertThat(docs.status()).isEqualTo(200);
        JsonNode operation =
                docs.json().path("paths").path("/api/v1/airports/{icao}").path("get");
        assertThat(operation.path("summary").asString()).isEqualTo("Get airport by ICAO code");
        assertThat(operation.path("parameters").get(0).path("description").asString())
                .contains("Four-letter ICAO code");
        assertThat(operation.path("responses").propertyNames())
                .containsExactlyInAnyOrder("200", "400", "404", "500", "502", "503", "504");
        assertThat(operation.path("responses").path("503").path("headers").has("Retry-After"))
                .isTrue();
        assertThat(docs.json().path("components").path("schemas").has("Problem"))
                .isTrue();
    }
}
