package com.msvieira.aviation.domain;

import java.util.List;
import java.util.Objects;

public record Airport(
        IcaoCode icao,
        String iata,
        String name,
        String state,
        String country,
        double latitude,
        double longitude,
        Integer elevationMeters,
        List<Runway> runways,
        String source) {

    public Airport {
        Objects.requireNonNull(icao, "icao must not be null");
        Objects.requireNonNull(name, "name must not be null");
        runways = runways == null ? List.of() : List.copyOf(runways);
    }
}
