package com.msvieira.aviation.domain.exception;

public class UpstreamUnavailableException extends UpstreamException {

    public UpstreamUnavailableException(final String provider, final String message) {
        super(provider, message);
    }

    public UpstreamUnavailableException(final String provider, final String message, final Throwable cause) {
        super(provider, message, cause);
    }
}
