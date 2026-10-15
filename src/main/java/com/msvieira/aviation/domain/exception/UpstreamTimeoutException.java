package com.msvieira.aviation.domain.exception;

public class UpstreamTimeoutException extends UpstreamException {

    public UpstreamTimeoutException(final String provider, final Throwable cause) {
        super(provider, "request timed out", cause);
    }
}
