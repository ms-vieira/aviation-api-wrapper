package com.msvieira.aviation.adapter.aviationweather;

import com.msvieira.aviation.domain.exception.UpstreamBadResponseException;
import com.msvieira.aviation.domain.exception.UpstreamRateLimitedException;
import com.msvieira.aviation.domain.exception.UpstreamUnavailableException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.msvieira.aviation.adapter.aviationweather.AviationWeatherAirportProvider.PROVIDER;

public class AviationWeatherStatusHandler implements ResponseErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(AviationWeatherStatusHandler.class);
    private static final int MAX_LOGGED_BODY = 256;

    @Override
    public boolean hasError(final ClientHttpResponse response) throws IOException {
        return response.getStatusCode().isError();
    }

    @Override
    public void handleError(final URI url, final HttpMethod method, final ClientHttpResponse response)
            throws IOException {
        final HttpStatusCode status = response.getStatusCode();

        if (status.value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            final Duration retryAfter = retryAfterSeconds(response);
            log.warn("{} rate-limited {} {} (Retry-After={})", PROVIDER, method, url, retryAfter);
            throw new UpstreamRateLimitedException(PROVIDER, retryAfter);
        }
        if (status.is5xxServerError()) {
            log.warn("{} answered {} for {} {}", PROVIDER, status.value(), method, url);
            throw new UpstreamUnavailableException(PROVIDER, "server error " + status.value());
        }

        log.error("{} rejected {} {} with {}: {}", PROVIDER, method, url, status.value(), safeBody(response));
        throw new UpstreamBadResponseException(PROVIDER, "unexpected status " + status.value());
    }

    private static @Nullable Duration retryAfterSeconds(final ClientHttpResponse response) {
        final String raw = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            final long seconds = Long.parseLong(raw.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String safeBody(final ClientHttpResponse response) {
        try (var in = response.getBody()) {
            final byte[] bytes = in.readNBytes(MAX_LOGGED_BODY);
            return new String(bytes, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        } catch (IOException e) {
            return "<unreadable body>";
        }
    }
}
