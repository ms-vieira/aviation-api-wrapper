package com.msvieira.aviation.application;

import com.msvieira.aviation.domain.Airport;
import com.msvieira.aviation.domain.AirportProvider;
import com.msvieira.aviation.domain.IcaoCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class AirportService {

    public static final String AIRPORTS_CACHE = "airports";

    private static final Logger log = LoggerFactory.getLogger(AirportService.class);

    private final AirportProvider provider;

    public AirportService(AirportProvider provider) {
        this.provider = provider;
    }

    @Cacheable(cacheNames = AIRPORTS_CACHE, key = "#icao.value()", sync = true)
    public Airport getByIcao(final IcaoCode icao) {
        log.debug("Cache miss for {}, querying provider", icao);
        return provider.findByIcao(icao);
    }
}
