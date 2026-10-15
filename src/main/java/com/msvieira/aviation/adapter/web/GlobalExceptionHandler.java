package com.msvieira.aviation.adapter.web;

import com.msvieira.aviation.domain.exception.AirportNotFoundException;
import com.msvieira.aviation.domain.exception.InvalidIcaoCodeException;
import com.msvieira.aviation.domain.exception.UpstreamBadResponseException;
import com.msvieira.aviation.domain.exception.UpstreamException;
import com.msvieira.aviation.domain.exception.UpstreamRateLimitedException;
import com.msvieira.aviation.domain.exception.UpstreamTimeoutException;
import com.msvieira.aviation.domain.exception.UpstreamUnavailableException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_PREFIX = "urn:aviation-api-wrapper:problem:";

    private final RateLimiterRegistry rateLimiterRegistry;

    public GlobalExceptionHandler(final RateLimiterRegistry rateLimiterRegistry) {
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    @ExceptionHandler(InvalidIcaoCodeException.class)
    ProblemDetail invalidIcao(final InvalidIcaoCodeException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-icao-code", "Invalid ICAO code", e.getMessage());
    }

    @ExceptionHandler(AirportNotFoundException.class)
    ProblemDetail notFound(final AirportNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "airport-not-found", "Airport not found", e.getMessage());
    }

    @ExceptionHandler(UpstreamBadResponseException.class)
    ProblemDetail badResponse(final UpstreamBadResponseException e) {
        log.error("Upstream contract problem: {}", e.getMessage(), e);
        return problem(
                HttpStatus.BAD_GATEWAY,
                "upstream-bad-response",
                "Upstream returned an unusable response",
                "The aviation data provider returned an unexpected response. The issue has been logged.");
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    ProblemDetail unavailable(final UpstreamUnavailableException e) {
        log.warn("Upstream unavailable: {}", e.getMessage());
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "upstream-unavailable",
                "Upstream unavailable",
                "The aviation data provider is currently unavailable. Please retry later.");
    }

    @ExceptionHandler(UpstreamRateLimitedException.class)
    ResponseEntity<ProblemDetail> rateLimited(final UpstreamRateLimitedException e) {
        log.warn("Upstream rate limited: {}", e.getMessage());
        final ProblemDetail body = problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "upstream-rate-limited",
                "Upstream rate limit reached",
                "The aviation data provider is throttling requests. Please retry later.");
        final HttpHeaders headers = new HttpHeaders();
        e.getRetryAfter().ifPresent(d -> headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(d.toSeconds())));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .headers(headers)
                .body(body);
    }

    @ExceptionHandler(CallNotPermittedException.class)
    ProblemDetail circuitOpen(final CallNotPermittedException e) {
        log.warn(
                "Circuit breaker '{}' is open, request rejected without calling upstream",
                e.getCausingCircuitBreakerName());
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "upstream-circuit-open",
                "Upstream temporarily disabled",
                "The aviation data provider has been failing repeatedly; requests are paused briefly to let it recover.");
    }

    @ExceptionHandler(RequestNotPermitted.class)
    ResponseEntity<ProblemDetail> outboundBudget(final RequestNotPermitted e) {
        log.warn("Outbound rate limit reached, request rejected without calling upstream: {}", e.getMessage());
        final ProblemDetail body = problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "outbound-rate-limited",
                "Outbound rate limit reached",
                "This instance has used its local allowance of calls to the aviation data provider. Please retry later.");
        final Duration refresh = rateLimiterRegistry
                .rateLimiter(e.getCausingRateLimiterName())
                .getRateLimiterConfig()
                .getLimitRefreshPeriod();
        final HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds(refresh)));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .headers(headers)
                .body(body);
    }

    @ExceptionHandler(BulkheadFullException.class)
    ProblemDetail saturated(final BulkheadFullException e) {
        log.warn("Concurrency limit reached for upstream calls, request rejected: {}", e.getMessage());
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "upstream-saturated",
                "Too many concurrent upstream calls",
                "The service is at its concurrency limit towards the aviation data provider. Please retry shortly.");
    }

    @ExceptionHandler(UpstreamTimeoutException.class)
    ProblemDetail timeout(final UpstreamTimeoutException e) {
        log.warn("Upstream timeout: {}", e.getMessage());
        return problem(
                HttpStatus.GATEWAY_TIMEOUT,
                "upstream-timeout",
                "Upstream timed out",
                "The aviation data provider did not answer in time. Please retry later.");
    }

    @ExceptionHandler(UpstreamException.class)
    ProblemDetail upstreamFallback(final UpstreamException e) {
        log.error("Unmapped upstream failure from provider '{}'", e.getProvider(), e);
        return problem(
                HttpStatus.BAD_GATEWAY,
                "upstream-error",
                "Upstream error",
                "The aviation data provider failed in an unexpected way. The issue has been logged.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(final Exception e) {
        log.error("Unhandled exception", e);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal-error",
                "Internal server error",
                "An unexpected error occurred. The issue has been logged.");
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleNoResourceFoundException(
            final NoResourceFoundException ex,
            final HttpHeaders headers,
            final HttpStatusCode status,
            final WebRequest request) {
        final ProblemDetail body = problem(
                HttpStatus.NOT_FOUND,
                "resource-not-found",
                "Resource not found",
                "No endpoint for " + ex.getHttpMethod() + " /" + ex.getResourcePath());
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            final @Nullable Object body,
            final HttpHeaders headers,
            final HttpStatusCode statusCode,
            final WebRequest request) {
        if (body instanceof ProblemDetail pd && pd.getType() == null) {
            decorate(pd, slugFor(statusCode));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static String slugFor(final HttpStatusCode statusCode) {
        final HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status == null
                ? "http-" + statusCode.value()
                : status.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static long retryAfterSeconds(final Duration refresh) {
        final long millis = Math.max(1, refresh.toMillis());
        return Math.max(1, (millis + 999) / 1000);
    }

    private static ProblemDetail problem(
            final HttpStatus status, final String slug, final String title, final String detail) {
        final ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return decorate(pd, slug);
    }

    private static ProblemDetail decorate(final ProblemDetail pd, final String slug) {
        pd.setType(URI.create(TYPE_PREFIX + slug));
        pd.setProperty("timestamp", Instant.now().toString());
        final String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            pd.setProperty("correlationId", correlationId);
        }
        return pd;
    }
}
