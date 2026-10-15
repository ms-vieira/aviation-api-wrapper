package com.msvieira.aviation.domain;

public interface AirportProvider {

    Airport findByIcao(final IcaoCode icao);
}
