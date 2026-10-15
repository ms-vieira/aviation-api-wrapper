package com.msvieira.aviation.domain.exception;

import com.msvieira.aviation.domain.IcaoCode;

public class AirportNotFoundException extends RuntimeException {

    public AirportNotFoundException(final IcaoCode icao) {
        super("No airport record available for ICAO code '" + icao + "'");
    }
}
