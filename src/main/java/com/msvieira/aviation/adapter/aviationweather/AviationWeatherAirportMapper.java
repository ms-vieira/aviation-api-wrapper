package com.msvieira.aviation.adapter.aviationweather;

import com.msvieira.aviation.domain.Airport;
import com.msvieira.aviation.domain.IcaoCode;
import com.msvieira.aviation.domain.Runway;
import com.msvieira.aviation.domain.exception.UpstreamBadResponseException;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

final class AviationWeatherAirportMapper {

    private AviationWeatherAirportMapper() {}

    static Airport toDomain(final @Nullable AviationWeatherAirportDto dto, final IcaoCode requested) {
        if (dto == null) {
            throw badRecord(requested, "record is null");
        }
        final String icaoId = blankToNull(dto.icaoId());
        if (icaoId == null || !icaoId.equalsIgnoreCase(requested.value())) {
            throw badRecord(requested, "record identifies itself as '" + dto.icaoId() + "'");
        }
        final String name = blankToNull(dto.name());
        if (name == null) {
            throw badRecord(requested, "name is missing");
        }
        if (!isLatitude(dto.lat()) || !isLongitude(dto.lon())) {
            throw badRecord(
                    requested, "coordinates are missing or out of range (" + dto.lat() + ", " + dto.lon() + ")");
        }
        final List<Runway> runways = dto.runways() == null
                ? List.of()
                : dto.runways().stream()
                        .filter(Objects::nonNull)
                        .map(r -> new Runway(r.id(), r.dimension(), r.surface()))
                        .toList();

        return new Airport(
                requested,
                blankToNull(dto.iataId()),
                name,
                blankToNull(dto.state()),
                blankToNull(dto.country()),
                dto.lat(),
                dto.lon(),
                dto.elev(),
                runways,
                AviationWeatherAirportProvider.PROVIDER);
    }

    private static boolean isLatitude(final @Nullable Double value) {
        return value != null && Double.isFinite(value) && Math.abs(value) <= 90.0;
    }

    private static boolean isLongitude(final @Nullable Double value) {
        return value != null && Double.isFinite(value) && Math.abs(value) <= 180.0;
    }

    private static UpstreamBadResponseException badRecord(final IcaoCode requested, final String reason) {
        return new UpstreamBadResponseException(
                AviationWeatherAirportProvider.PROVIDER, "unusable record for " + requested + ": " + reason);
    }

    private static @Nullable String blankToNull(final @Nullable String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
