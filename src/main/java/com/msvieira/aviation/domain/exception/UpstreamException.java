package com.msvieira.aviation.domain.exception;

public abstract class UpstreamException extends RuntimeException {

    private final String provider;

    protected UpstreamException(final String provider, final String message) {
        super("[" + provider + "] " + message);
        this.provider = provider;
    }

    protected UpstreamException(final String provider, final String message, final Throwable cause) {
        super("[" + provider + "] " + message, cause);
        this.provider = provider;
    }

    public String getProvider() {
        return provider;
    }
}
