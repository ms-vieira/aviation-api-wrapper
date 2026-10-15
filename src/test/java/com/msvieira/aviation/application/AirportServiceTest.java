package com.msvieira.aviation.application;

import com.msvieira.aviation.domain.Airport;
import com.msvieira.aviation.domain.AirportProvider;
import com.msvieira.aviation.domain.IcaoCode;
import com.msvieira.aviation.domain.exception.AirportNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AirportServiceTest {

    @Mock
    AirportProvider provider;

    @InjectMocks
    AirportService service;

    @Test
    void delegatesToProvider() {
        final IcaoCode icao = IcaoCode.of("KJFK");
        final Airport airport = new Airport(icao, "JFK", "JFK INTL", "NY", "US", 40.6, -73.7, 4, List.of(), "test");
        when(provider.findByIcao(icao)).thenReturn(airport);

        assertThat(service.getByIcao(icao)).isSameAs(airport);
        verify(provider).findByIcao(icao);
    }

    @Test
    void propagatesProviderExceptionsUnchanged() {
        final IcaoCode icao = IcaoCode.of("ZZZZ");
        when(provider.findByIcao(icao)).thenThrow(new AirportNotFoundException(icao));

        assertThatThrownBy(() -> service.getByIcao(icao)).isInstanceOf(AirportNotFoundException.class);
    }
}
