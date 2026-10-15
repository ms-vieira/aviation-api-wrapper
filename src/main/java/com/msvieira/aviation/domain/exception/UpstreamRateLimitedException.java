package com.msvieira.aviation.domain.exception;

import java.time.Duration;
import java.util.Optional;

public class UpstreamRateLimitedException extends UpstreamException {

    private final Duration retryAfter;

    public UpstreamRateLimitedException(final String provider, final Duration retryAfter) {
        super(provider, "rate limit exceeded" + (retryAfter != null ? ", retry after " + retryAfter : ""));
        this.retryAfter = retryAfter;
    }

    public Optional<Duration> getRetryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
