package com.msvieira.aviation.adapter.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.msvieira.aviation.domain.Airport;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(name = "Airport")
public record AirportResponse(
        @Schema(example = "KJFK", description = "ICAO location indicator")
        String icao,

        @Schema(example = "JFK", description = "IATA code, when known", nullable = true)
        String iata,

        @Schema(example = "NEW YORK/JOHN F KENNEDY INTL") String name,

        @Schema(example = "NY", description = "State/region as reported by the provider", nullable = true)
        String state,

        @Schema(example = "US", description = "ISO 3166-1 alpha-2 country code", nullable = true)
        String country,

        @Schema(example = "40.6399") double latitude,
        @Schema(example = "-73.7787") double longitude,

        @Schema(example = "4", description = "Field elevation in metres", nullable = true)
        Integer elevationMeters,

        List<RunwayResponse> runways,

        @Schema(example = "aviationweather", description = "Data provider that served this record")
        String source) {

    @Schema(name = "Runway")
    public record RunwayResponse(
            @Schema(example = "04L/22R") String id,

            @Schema(
                    example = "12079x200",
                    description = "Length x width as reported by the provider (feet for FAA data)")
            String dimension,

            @Schema(example = "C", description = "Surface code as reported by the provider")
            String surface) {}

    public static AirportResponse from(final Airport airport) {
        return new AirportResponse(
                airport.icao().value(),
                airport.iata(),
                airport.name(),
                airport.state(),
                airport.country(),
                airport.latitude(),
                airport.longitude(),
                airport.elevationMeters(),
                airport.runways().stream()
                        .map(r -> new RunwayResponse(r.id(), r.dimension(), r.surface()))
                        .toList(),
                airport.source());
    }
}
