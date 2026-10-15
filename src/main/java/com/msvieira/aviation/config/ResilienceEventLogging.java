package com.msvieira.aviation.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import io.github.resilience4j.retry.Retry;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ResilienceEventLogging {

    private static final Logger log = LoggerFactory.getLogger(ResilienceEventLogging.class);

    @Bean
    RegistryEventConsumer<CircuitBreaker> circuitBreakerEventLogger() {
        return new RegistryEventConsumer<>() {
            @Override
            public void onEntryAddedEvent(final EntryAddedEvent<CircuitBreaker> event) {
                attach(event.getAddedEntry());
            }

            @Override
            public void onEntryRemovedEvent(final EntryRemovedEvent<CircuitBreaker> event) {}

            @Override
            public void onEntryReplacedEvent(final EntryReplacedEvent<CircuitBreaker> event) {
                attach(event.getNewEntry());
            }
        };
    }

    @Bean
    RegistryEventConsumer<Retry> retryEventLogger() {
        return new RegistryEventConsumer<>() {
            @Override
            public void onEntryAddedEvent(final @NonNull EntryAddedEvent<Retry> event) {
                attach(event.getAddedEntry());
            }

            @Override
            public void onEntryRemovedEvent(final @NonNull EntryRemovedEvent<Retry> event) {}

            @Override
            public void onEntryReplacedEvent(final @NonNull EntryReplacedEvent<Retry> event) {
                attach(event.getNewEntry());
            }
        };
    }

    private static void attach(final CircuitBreaker cb) {
        cb.getEventPublisher()
                .onStateTransition(e -> log.warn(
                        "Circuit breaker '{}' {} -> {}",
                        cb.getName(),
                        e.getStateTransition().getFromState(),
                        e.getStateTransition().getToState()))
                .onCallNotPermitted(e -> log.debug("Circuit breaker '{}' rejected a call", cb.getName()));
    }

    private static void attach(final Retry retry) {
        retry.getEventPublisher()
                .onRetry(e -> log.warn(
                        "Retry '{}' attempt {} after {}: {}",
                        retry.getName(),
                        e.getNumberOfRetryAttempts(),
                        e.getWaitInterval(),
                        e.getLastThrowable() == null
                                ? "n/a"
                                : e.getLastThrowable().getMessage()))
                .onError(e -> log.warn(
                        "Retry '{}' exhausted after {} attempts", retry.getName(), e.getNumberOfRetryAttempts()));
    }
}
