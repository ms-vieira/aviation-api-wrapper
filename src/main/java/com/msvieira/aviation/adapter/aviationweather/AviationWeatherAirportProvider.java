package com.msvieira.aviation.adapter.aviationweather;

import com.msvieira.aviation.domain.Airport;
import com.msvieira.aviation.domain.AirportProvider;
import com.msvieira.aviation.domain.IcaoCode;
import com.msvieira.aviation.domain.exception.AirportNotFoundException;
import com.msvieira.aviation.domain.exception.UpstreamBadResponseException;
import com.msvieira.aviation.domain.exception.UpstreamTimeoutException;
import com.msvieira.aviation.domain.exception.UpstreamUnavailableException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpTimeoutException;
import java.util.List;

@Component
public class AviationWeatherAirportProvider implements AirportProvider {

    public static final String PROVIDER = "aviationweather";
    static final String RESILIENCE_NAME = "aviationWeather";

    private static final Logger log = LoggerFactory.getLogger(AviationWeatherAirportProvider.class);
    private static final ParameterizedTypeReference<List<AviationWeatherAirportDto>> AIRPORT_LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public AviationWeatherAirportProvider(final RestClient aviationWeatherRestClient) {
        this.restClient = aviationWeatherRestClient;
    }

    @Override
    @Retry(name = RESILIENCE_NAME)
    @CircuitBreaker(name = RESILIENCE_NAME)
    @RateLimiter(name = RESILIENCE_NAME)
    @Bulkhead(name = RESILIENCE_NAME)
    public Airport findByIcao(final IcaoCode icao) {
        log.debug("GET airport {} from {}", icao, PROVIDER);
        final List<AviationWeatherAirportDto> airports = fetch(icao);
        if (airports == null || airports.isEmpty()) {
            throw new AirportNotFoundException(icao);
        }
        return AviationWeatherAirportMapper.toDomain(airports.getFirst(), icao);
    }

    private List<AviationWeatherAirportDto> fetch(final IcaoCode icao) {
        try {
            return restClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/data/airport")
                            .queryParam("ids", icao.value())
                            .queryParam("format", "json")
                            .build())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(AIRPORT_LIST);
        } catch (ResourceAccessException e) {
            throw translateIoFailure(e);
        } catch (RestClientException e) {
            log.error("{} returned an undecodable body for {}: {}", PROVIDER, icao, e.getMessage());
            throw new UpstreamBadResponseException(PROVIDER, "could not decode response body", e);
        }
    }

    private static RuntimeException translateIoFailure(final ResourceAccessException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException) {
                return new UpstreamTimeoutException(PROVIDER, e);
            }
        }
        return new UpstreamUnavailableException(PROVIDER, "I/O failure: " + e.getMessage(), e);
    }
}
