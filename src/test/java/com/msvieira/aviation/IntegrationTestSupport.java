package com.msvieira.aviation;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.msvieira.aviation.adapter.web.CorrelationIdFilter;
import com.msvieira.aviation.application.AirportService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
abstract class IntegrationTestSupport {

    static final String UPSTREAM_PATH = "/api/data/airport";
    static final String CB_NAME = "aviationWeather";
    static final WireMockServer wireMock = startedWireMock();

    @LocalServerPort
    int port;

    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    RateLimiterRegistry rateLimiterRegistry;

    @Autowired
    CacheManager cacheManager;

    RestClient http;

    private static WireMockServer startedWireMock() {
        WireMockServer server =
                new WireMockServer(WireMockConfiguration.options().dynamicPort().globalTemplating(true));
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        return server;
    }

    @DynamicPropertySource
    static void upstreamUrl(DynamicPropertyRegistry registry) {
        registry.add("aviation.provider.aviationweather.base-url", wireMock::baseUrl);
    }

    @BeforeEach
    void resetState() {
        wireMock.resetAll();
        circuitBreakerRegistry.circuitBreaker(CB_NAME).reset();
        rateLimiterRegistry.remove(CB_NAME);
        Optional.ofNullable(cacheManager.getCache(AirportService.AIRPORTS_CACHE))
                .ifPresent(Cache::clear);
        http = RestClient.create("http://localhost:" + port);
    }

    void stubAirport(String icao, ResponseDefinitionBuilder response) {
        wireMock.stubFor(get(urlPathEqualTo(UPSTREAM_PATH))
                .withQueryParam("ids", equalTo(icao))
                .willReturn(response));
    }

    void stubAnyAirport(ResponseDefinitionBuilder response) {
        wireMock.stubFor(get(urlPathEqualTo(UPSTREAM_PATH)).willReturn(response));
    }

    void limitOutboundCalls(int permits) {
        limitOutboundCalls(permits, Duration.ofMinutes(1));
    }

    void limitOutboundCalls(int permits, Duration refreshPeriod) {
        rateLimiterRegistry.remove(CB_NAME);
        rateLimiterRegistry.rateLimiter(
                CB_NAME,
                RateLimiterConfig.custom()
                        .limitForPeriod(permits)
                        .limitRefreshPeriod(refreshPeriod)
                        .timeoutDuration(Duration.ZERO)
                        .build());
    }

    static String fixtureForRequestedCode() {
        return fixture().replace("\"icaoId\": \"KJFK\"", "\"icaoId\": \"{{request.query.ids}}\"");
    }

    Response call(String path) {
        return http.get().uri(path).exchange((req, res) -> Response.of(res));
    }

    void assertProblem(Response res, String typeSlug) {
        assertThat(res.contentType()).hasToString(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode body = res.json();
        assertThat(body.path("type").asString()).isEqualTo("urn:aviation-api-wrapper:problem:" + typeSlug);
        assertThat(body.path("status").asInt()).isEqualTo(res.status());
        assertThat(body.path("title").asString()).isNotBlank();
        assertThat(body.path("detail").asString()).isNotBlank();
        assertThat(body.path("correlationId").asString()).isNotBlank();
        assertThat(body.path("correlationId").asString())
                .isEqualTo(res.headers().getFirst(CorrelationIdFilter.HEADER));
    }

    static String fixture() {
        try (var in = IntegrationTestSupport.class.getClassLoader().getResourceAsStream("wiremock/kjfk.json")) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture wiremock/kjfk.json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    record Response(int status, HttpHeaders headers, String body) {

        private static final ObjectMapper JSON = new ObjectMapper();

        static Response of(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse res) throws IOException {
            String body = res.bodyTo(String.class);
            return new Response(res.getStatusCode().value(), res.getHeaders(), body == null ? "" : body);
        }

        MediaType contentType() {
            MediaType ct = headers.getContentType();
            return ct == null ? null : new MediaType(ct.getType(), ct.getSubtype());
        }

        JsonNode json() {
            return JSON.readTree(body);
        }
    }
}
