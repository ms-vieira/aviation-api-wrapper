package com.msvieira.aviation.domain.exception;

public class UpstreamBadResponseException extends UpstreamException {

    public UpstreamBadResponseException(final String provider, final String message) {
        super(provider, message);
    }

    public UpstreamBadResponseException(final String provider, final String message, final Throwable cause) {
        super(provider, message, cause);
    }
}
