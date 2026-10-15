package com.msvieira.aviation.adapter.aviationweather;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record AviationWeatherAirportDto(
        String icaoId,
        String iataId,
        String name,
        String state,
        String country,
        Double lat,
        Double lon,
        Integer elev,
        List<RunwayDto> runways) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RunwayDto(String id, String dimension, String surface) {}
}
