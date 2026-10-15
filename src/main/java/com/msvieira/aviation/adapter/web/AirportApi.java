package com.msvieira.aviation.adapter.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;

@Tag(name = "Airports", description = "Airport lookup by ICAO code")
public interface AirportApi {

    @Operation(
            summary = "Get airport by ICAO code",
            description = "Looks up an airport by its four-letter ICAO location indicator. "
                    + "The code is case-insensitive. Errors follow RFC 9457 Problem Details; "
                    + "provider-related failures (502/503/504) are shared by every operation.")
    @ApiResponse(responseCode = "200", description = "Airport found")
    @ApiResponse(
            responseCode = "404",
            description = "No record for this code at the configured provider",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(ref = ProblemDocumentation.SCHEMA_REF)))
    AirportResponse getByIcao(
            @Parameter(description = "Four-letter ICAO code, e.g. KJFK or sbgr", example = "KJFK") String icao);
}
