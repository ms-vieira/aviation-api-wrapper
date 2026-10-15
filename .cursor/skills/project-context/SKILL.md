---
name: project-context
description: Architecture, layering rules, conventions, error contract and resilience policy of the aviation-api-wrapper service. Use before changing, reviewing or extending any code in this repository, and as the shared reference for the create-endpoint, create-external-integration, write-tests and code-review skills.
---

# Project context: aviation-api-wrapper

Spring Boot microservice that returns airport details for an ICAO code, backed by
aviationweather.gov. Evaluated on scalability, resilience, extensibility and observability,
not on feature count. Every change must preserve those four properties.

## Stack (do not guess versions, read `build.gradle.kts`)

Java 25, Spring Boot 4.x, Gradle Kotlin DSL, Resilience4j (`resilience4j-spring-boot4`),
Caffeine via Spring Cache, Micrometer + Prometheus, springdoc-openapi, WireMock (tests),
Spotless (palantir-java-format).

Boot 4 specifics that differ from Boot 3:

- Starters: `spring-boot-starter-webmvc`, `-restclient`, `-aspectj` (not `-aop`),
  `-micrometer-metrics`, `-webmvc-test`, `-restclient-test`.
- JSON is Jackson 3: `tools.jackson.databind.*` (annotations stay `com.fasterxml.jackson.annotation.*`).
- JUnit 6 (Jupiter). Mockito runs as a Java agent (configured in Gradle).

## Layers and dependency rule

```
adapter.web ──► application ──► domain ◄── adapter.<provider>
                                  ▲
config (wiring only) ─────────────┘
```

| Package | Contains | May depend on |
|---|---|---|
| `domain` | `Airport`, `Runway`, `IcaoCode`, port `AirportProvider`, `exception.*` | nothing but the JDK |
| `application` | `AirportService` (use-case orchestration, `@Cacheable`) | `domain` |
| `adapter.web` | `AirportApi` (OpenAPI contract), controller, response records, `ProblemDocumentation`, `GlobalExceptionHandler`, `CorrelationIdFilter` | `application`, `domain` |
| `adapter.<provider>` | provider implementing `AirportProvider`, `<Vendor>StatusHandler` (`ResponseErrorHandler`), DTOs, mapper, properties | `domain` |
| `config` | `@Configuration` beans: RestClient, cache, OpenAPI, resilience event logging | anything |

Rules:

- `domain` never imports Spring, Jackson, Resilience4j or HTTP types.
- Adapters translate every transport outcome into a domain exception. Nothing above the port
  may see `ResourceAccessException`, status codes or provider DTOs.
- No interfaces with a single implementation except ports. No pass-through classes.
- Validation happens at the edge: the controller builds the value object (`IcaoCode.of(raw)`),
  so invalid input never reaches the service, cache or provider.
- Stateless: the only state is the local cache, which is an optimisation.

## Domain exceptions and HTTP mapping (RFC 9457 Problem Details)

| Exception | HTTP | `type` suffix | Retried | Counted by breaker |
|---|---|---|---|---|
| `InvalidIcaoCodeException` | 400 | `invalid-icao-code` | - | - |
| `AirportNotFoundException` | 404 | `airport-not-found` | no | ignored |
| `UpstreamBadResponseException` | 502 | `upstream-bad-response` | no | yes |
| `UpstreamUnavailableException` | 503 | `upstream-unavailable` | yes | yes |
| `UpstreamRateLimitedException` | 503 + `Retry-After` | `upstream-rate-limited` | no | yes |
| `CallNotPermittedException` (R4j) | 503 | `upstream-circuit-open` | no | - |
| `BulkheadFullException` (R4j) | 503 | `upstream-saturated` | no | ignored |
| `RequestNotPermitted` (R4j) | 503 | `outbound-rate-limited` | no | ignored |
| `UpstreamTimeoutException` | 504 | `upstream-timeout` | yes | yes |
| other `UpstreamException` (safety net) | 502 | `upstream-error` | - | - |
| Spring MVC exceptions (route, method, `Accept`, parsing) | 404/405/406/400... | slug derived from the status, e.g. `resource-not-found`, `method-not-allowed` | - | - |
| anything else | 500 | `internal-error` | - | - |

All provider failures extend `UpstreamException` and carry the provider name.
`GlobalExceptionHandler` extends `ResponseEntityExceptionHandler`: Boot backs off its own
problem-details advice, MVC exceptions get the same Problem Details shape, and a new
`UpstreamException` subtype never leaks as a 500. Do not add `@Order` to it. Every problem
carries `correlationId` and `timestamp` (see `decorate`).

## Resilience policy (values in `application.yml`)

- Annotations on the adapter method: `@Retry` + `@CircuitBreaker` + `@RateLimiter` + `@Bulkhead`,
  same name. Default aspect order is `Retry(CircuitBreaker(RateLimiter(Bulkhead(method))))`:
  the breaker counts every attempt, and every attempt spends one rate-limit permit.
- Retry only `UpstreamTimeout` and `UpstreamUnavailable`. Never retry not-found, 429, bad
  response, `CallNotPermittedException`, `BulkheadFullException` or `RequestNotPermitted`.
- Timeouts are per attempt (connect/read on the `RestClient`); there is no global deadline.
- Breaker: `minimumNumberOfCalls`, ignore `AirportNotFound`, `BulkheadFull` and
  `RequestNotPermitted`, `registerHealthIndicator: true`, `allowHealthIndicatorToFail: false`.
- Bulkhead (32 permits, 500 ms wait) caps in-flight provider calls because requests run on
  virtual threads (`spring.threads.virtual.enabled=true`) and no thread pool limits them.
- Rate limiter is local: 10 calls / 8 s / no wait, 503 `outbound-rate-limited` with
  `Retry-After` set to the cycle length. A 100/minute bucket is not used: it refills in full at
  the boundary and can send 200 calls inside one real minute. The counter does not add up
  across replicas.
- Cache only successes; key is the normalised code; `sync = true` collapses concurrent misses.

## Observability

Actuator `health` (with breaker component, probes enabled), `metrics`, `prometheus`, `info`.
Logs carry `[cid=<correlationId>]` from MDC; breaker transitions and retries are logged by
`ResilienceEventLogging`. Container `HEALTHCHECK` uses `/actuator/health/liveness`.

## Commands

```bash
./gradlew build            # compile + spotlessCheck + tests
./gradlew spotlessApply    # fix formatting (required before commit)
./gradlew test --tests '*IntegrationTest'
```

## Coding conventions

See [conventions.md](conventions.md). Summary: no comments or Javadoc, `final` parameters,
records for data, constructor injection, SLF4J with placeholders, conventional commits.
