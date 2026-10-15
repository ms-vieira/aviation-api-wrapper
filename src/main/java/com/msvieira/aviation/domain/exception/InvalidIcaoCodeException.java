package com.msvieira.aviation.domain.exception;

public class InvalidIcaoCodeException extends RuntimeException {

    public InvalidIcaoCodeException(final String rejectedValue) {
        super("Invalid ICAO code '" + rejectedValue + "': expected exactly four letters (A-Z)");
    }
}
