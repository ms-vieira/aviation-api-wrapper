package com.msvieira.aviation.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.msvieira.aviation.application.AirportService;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager(final AirportsCacheProperties props) {
        final CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCacheNames(List.of(AirportService.AIRPORTS_CACHE));
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(props.maximumSize())
                .expireAfterWrite(props.ttl())
                .recordStats());
        return manager;
    }

    @ConfigurationProperties(prefix = "aviation.cache")
    public record AirportsCacheProperties(long maximumSize, Duration ttl) {

        public AirportsCacheProperties {
            if (maximumSize <= 0) {
                maximumSize = 10_000;
            }
            if (ttl == null || ttl.isNegative() || ttl.isZero()) {
                ttl = Duration.ofMinutes(10);
            }
        }
    }
}
