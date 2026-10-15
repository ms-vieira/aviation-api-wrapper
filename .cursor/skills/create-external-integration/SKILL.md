---
name: create-external-integration
description: Add or change an outbound integration (third-party HTTP provider, upstream API, external data source) in aviation-api-wrapper, including the port/adapter split, RestClient wiring, exception translation, Resilience4j retry and circuit breaker configuration and WireMock tests. Use when asked to integrate a new provider, call an external API, add a fallback provider or modify how the service talks to aviationweather.gov.
---

# Create an external integration

Read [project-context](../project-context/SKILL.md) first. The existing
`adapter.aviationweather` package is the reference implementation; mirror its shape.

## Checklist

```
- [ ] 1. Port: reuse AirportProvider or define a new domain interface
- [ ] 2. Properties record + application.yml (+ test override)
- [ ] 3. RestClient bean in config with per-attempt timeouts and User-Agent
- [ ] 4. DTO (package-private record) + static mapper to the domain model
- [ ] 5. Adapter: translate EVERY outcome into a domain exception
- [ ] 6. Resilience4j instance: retry + circuit breaker in application.yml
- [ ] 7. Tests: mapper unit test + WireMock integration scenarios
- [ ] 8. README (architecture, resilience, assumptions) and project-context updated
```

## Step 1: Port

The application depends on `domain.AirportProvider` only. A new vendor for the same capability
is a second implementation of that port; a new capability is a new port in `domain`. If two
providers must coexist, add a composite/fallback implementation in `adapter` and mark one bean
`@Primary`; never inject two providers into the service.

## Step 2: Properties

```java
@Validated
@ConfigurationProperties(prefix = "aviation.provider.<vendor>")
public record <Vendor>Properties(
        @NotBlank String baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotBlank String userAgent) {}
```

Add the block to `application.yml` and a short-timeout override to `application-test.yml`.
Never commit credentials; API keys come from environment variables referenced in YAML.

## Step 3: RestClient

One named bean per vendor in `config.RestClientConfig`, built from the auto-configured
`RestClient.Builder` (keeps `http_client_requests` metrics), with a `JdkClientHttpRequestFactory`
carrying connect/read timeouts, a default `User-Agent` and
`.defaultStatusHandler(new <Vendor>StatusHandler())`. Timeouts are **per attempt**.

## Step 4: DTO and mapper

- DTO is a package-private `record` annotated `@JsonIgnoreProperties(ignoreUnknown = true)`,
  containing only the fields we use, all nullable wrapper types.
- Mapper is `final` with a static `toDomain(dto, requestedKey)`; it trims strings, converts
  blank to `null`, and throws `UpstreamBadResponseException` when the record is null, the
  identifier is missing or does not match `requestedKey`, the name is blank, or coordinates are
  missing, non-finite or outside [-90, 90] / [-180, 180]. Never copy the requested key onto a
  record that identified itself as something else.
- Verify units and optionality against a **real** payload before naming domain fields
  (`elev` from aviationweather.gov is metres, not feet). Save the real payload as a test fixture.

## Step 5: Adapter and exception translation

Two classes, mirroring `adapter.aviationweather`. Do not extract helpers for a second provider
until one exists.

1. `<Vendor>StatusHandler implements ResponseErrorHandler` (registered on the RestClient):
   turns every 4xx/5xx into a domain exception, so the provider never sees status codes.
   `Retry-After` is parsed as delay-seconds only (`null` when absent, negative or not a number).
2. `<Vendor>AirportProvider`: builds the request, `retrieve().body(...)`, handles "no data",
   "undecodable body" and I/O failures, maps. The request asks for one id, so a non-empty list
   is passed to the mapper as its first element; the mapper, not the provider, checks that the
   record matches the requested key. A `ResourceAccessException` whose cause chain contains
   `java.net.http.HttpTimeoutException` (covers connect and read with the JDK client) becomes
   `UpstreamTimeoutException`; anything else becomes `UpstreamUnavailableException`.

Required mapping:

| Where | Outcome | Throw |
|---|---|---|
| mapper | null element, identifier missing/mismatch, blank name, coordinates missing/non-finite/out of range | `UpstreamBadResponseException` |
| provider | 204 / `null` / empty collection | `AirportNotFoundException` (or the port's not-found exception) |
| provider | `RestClientException` that is not a `ResourceAccessException` (undecodable body, unknown content type) | `UpstreamBadResponseException`, log at ERROR |
| provider | `ResourceAccessException` with `HttpTimeoutException` in the cause chain | `UpstreamTimeoutException` |
| provider | other `ResourceAccessException` | `UpstreamUnavailableException` |
| status handler | 429 | `UpstreamRateLimitedException`, `Retry-After` seconds forwarded when parseable |
| status handler | 5xx | `UpstreamUnavailableException` |
| status handler | other 4xx (400/401/403/404 on the endpoint) | `UpstreamBadResponseException`, log at ERROR with the first 256 bytes of the body |

Catch order matters: `ResourceAccessException` extends `RestClientException`, catch it first.
Annotate the port method with `@Retry(name = "<vendor>")` and `@CircuitBreaker(name = "<vendor>")`.
Expose `public static final String PROVIDER = "<vendor>"` and set it as `Airport.source`.

## Step 6: Resilience4j configuration

Copy the `aviationWeather` blocks in `application.yml` under the new instance name and keep:

- Retry: `retry-exceptions` = timeout + unavailable; `ignore-exceptions` = not-found,
  rate-limited, bad-response, `io.github.resilience4j.circuitbreaker.CallNotPermittedException`.
- Circuit breaker: `minimum-number-of-calls`, `record-exceptions` = the four `Upstream*`,
  `ignore-exceptions` = not-found, `register-health-indicator: true`,
  `allow-health-indicator-to-fail: false`.
- Bulkhead: `max-concurrent-calls` sized for the vendor's connection budget
  (`aviationWeather` uses 32 / 500 ms wait). Add `BulkheadFullException` to **both** ignore
  lists (retry and breaker); the handler maps it to 503 `upstream-saturated`.
- Rate limiter: local, `timeout-duration: 0`, one permit per attempt (the limiter sits inside
  retry). Do not set `limit-for-period` to the vendor's per-minute cap: a fixed cycle refills
  the whole bucket at the boundary. `aviationWeather` uses 10 / 8 s so the boundary burst stays
  under 100/minute. Add `RequestNotPermitted` to both ignore lists; the handler maps it to
  503 `outbound-rate-limited` and sets `Retry-After` to the cycle length, rounded up.
  Say in the README that replicas do not share the counter.
  Annotate `@RateLimiter(name = "<vendor>")` as well. No `TimeLimiter` (per-attempt timeouts
  live in the HTTP client).
- Document the worst-case latency: `attempts x (connect + read) + backoff sum`.

## Step 7: Tests

Mapper unit test (all fields, nulls, identifier mismatch, blank name, out-of-range coordinates)
plus WireMock integration
scenarios, at minimum: 200 with real fixture, not-found without retry (`verify(1)`), transient
5xx then 200 (`verify(3)`), timeout -> 504, 429 -> 503 + `Retry-After`, unexpected 4xx -> 502,
malformed body -> 502, breaker opens after `minimumNumberOfCalls` attempts.
See [write-tests](../write-tests/SKILL.md).

## Step 8: Documentation

README: architecture package list, resilience table, assumptions about the provider (rate
limits, quirks observed on real calls). Add the vendor to `project-context/SKILL.md`.

## Anti-patterns

- Letting `ResourceAccessException`, `HttpServerErrorException` or status codes cross the port.
- Retrying 429 or not-found; retrying with the circuit open.
- Catching `Exception` in the adapter and rethrowing a generic `RuntimeException`.
- Hard-coding the base URL (tests must point it at WireMock via `@DynamicPropertySource`).
- A separate `*Client` class that only wraps `RestClient` calls for the adapter.
