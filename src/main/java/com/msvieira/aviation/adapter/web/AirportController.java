package com.msvieira.aviation.adapter.web;

import com.msvieira.aviation.application.AirportService;
import com.msvieira.aviation.domain.IcaoCode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/airports", produces = MediaType.APPLICATION_JSON_VALUE)
public class AirportController implements AirportApi {

    private final AirportService airportService;

    public AirportController(final AirportService airportService) {
        this.airportService = airportService;
    }

    @Override
    @GetMapping("/{icao}")
    public AirportResponse getByIcao(@PathVariable final String icao) {
        return AirportResponse.from(airportService.getByIcao(IcaoCode.of(icao)));
    }
}
