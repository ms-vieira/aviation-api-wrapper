package com.msvieira.aviation.adapter.aviationweather;

import com.msvieira.aviation.domain.Airport;
import com.msvieira.aviation.domain.IcaoCode;
import com.msvieira.aviation.domain.Runway;
import com.msvieira.aviation.domain.exception.UpstreamBadResponseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AviationWeatherAirportMapperTest {

    private static final IcaoCode KJFK = IcaoCode.of("KJFK");

    @Test
    void mapsAllFieldsTrimmingNameAndKeepingElevationInMetres() {
        var dto = new AviationWeatherAirportDto(
                "KJFK",
                "JFK",
                "NEW YORK/JOHN F KENNEDY INTL ",
                "NY",
                "US",
                40.6399,
                -73.7787,
                4,
                List.of(new AviationWeatherAirportDto.RunwayDto("04L/22R", "12079x200", "C")));

        Airport airport = AviationWeatherAirportMapper.toDomain(dto, KJFK);

        assertThat(airport.icao()).isEqualTo(KJFK);
        assertThat(airport.iata()).isEqualTo("JFK");
        assertThat(airport.name()).isEqualTo("NEW YORK/JOHN F KENNEDY INTL");
        assertThat(airport.state()).isEqualTo("NY");
        assertThat(airport.country()).isEqualTo("US");
        assertThat(airport.latitude()).isEqualTo(40.6399);
        assertThat(airport.longitude()).isEqualTo(-73.7787);
        assertThat(airport.elevationMeters()).isEqualTo(4);
        assertThat(airport.runways()).containsExactly(new Runway("04L/22R", "12079x200", "C"));
        assertThat(airport.source()).isEqualTo("aviationweather");
    }

    @Test
    void toleratesNullOptionalFieldsAndIcaoIdCaseOrPadding() {
        var dto = new AviationWeatherAirportDto(" sbgr ", null, "GUARULHOS", "", null, -23.4, -46.4, null, null);

        Airport airport = AviationWeatherAirportMapper.toDomain(dto, IcaoCode.of("SBGR"));

        assertThat(airport.iata()).isNull();
        assertThat(airport.state()).isNull();
        assertThat(airport.country()).isNull();
        assertThat(airport.elevationMeters()).isNull();
        assertThat(airport.runways()).isEmpty();
    }

    @Test
    void rejectsNullRecord() {
        assertThatThrownBy(() -> AviationWeatherAirportMapper.toDomain(null, KJFK))
                .isInstanceOf(UpstreamBadResponseException.class)
                .hasMessageContaining("KJFK");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "KLAX"})
    void rejectsRecordsWhoseIdentifierIsMissingOrDifferentFromTheRequestedCode(String icaoId) {
        var dto = new AviationWeatherAirportDto(icaoId, null, "LOS ANGELES", null, null, 33.94, -118.4, null, null);

        assertThatThrownBy(() -> AviationWeatherAirportMapper.toDomain(dto, KJFK))
                .isInstanceOf(UpstreamBadResponseException.class)
                .hasMessageContaining("KJFK");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void rejectsRecordsWithoutAName(String name) {
        var dto = new AviationWeatherAirportDto("KJFK", null, name, null, null, 40.0, -73.0, null, null);

        assertThatThrownBy(() -> AviationWeatherAirportMapper.toDomain(dto, KJFK))
                .isInstanceOf(UpstreamBadResponseException.class)
                .hasMessageContaining("name");
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "null",
            value = {
                "null, -73.0",
                "40.0, null",
                "999.0, -999.0",
                "90.1, 0.0",
                "0.0, -180.1",
                "NaN, 0.0",
                "0.0, Infinity"
            })
    void rejectsMissingOrImpossibleCoordinates(Double lat, Double lon) {
        var dto = new AviationWeatherAirportDto("KJFK", null, "X", null, null, lat, lon, null, null);

        assertThatThrownBy(() -> AviationWeatherAirportMapper.toDomain(dto, KJFK))
                .isInstanceOf(UpstreamBadResponseException.class)
                .hasMessageContaining("coordinates");
    }

    @ParameterizedTest
    @CsvSource({"90.0, 180.0", "-90.0, -180.0", "0.0, 0.0"})
    void acceptsCoordinatesOnTheBoundaries(double lat, double lon) {
        var dto = new AviationWeatherAirportDto("KJFK", null, "X", null, null, lat, lon, null, null);

        assertThat(AviationWeatherAirportMapper.toDomain(dto, KJFK).latitude()).isEqualTo(lat);
    }
}
