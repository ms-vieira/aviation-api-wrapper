# Coding conventions

## Style

- Formatting is owned by Spotless (palantir-java-format, 120 columns). Never hand-format; run
  `./gradlew spotlessApply`.
- Import order: third-party, then `java|jakarta|javax`, then static. No wildcard imports.
- **No comments and no Javadoc.** Express intent through names, small methods and types.
  The only accepted exception is a one-line note explaining a non-obvious *workaround*
  (e.g. an ordering constraint imposed by a framework).
- Method and constructor parameters are `final`.
- Prefer `record` for immutable data (domain values, DTOs, responses, properties).
- Constructor injection only; no `@Autowired` on fields in production code.
- `private static final Logger log = LoggerFactory.getLogger(X.class);` with `{}` placeholders.
- Constants: `private static final`, SCREAMING_SNAKE_CASE. Public constants only when another
  layer legitimately needs them (e.g. cache name, header name).
- Null handling: return `null` only inside records that model optional provider fields;
  otherwise prefer `Optional` on read APIs and fail fast with `Objects.requireNonNull`.

## Naming

| Thing | Pattern | Example |
|---|---|---|
| Port | `<Thing>Provider` / `<Thing>Repository` | `AirportProvider` |
| Adapter | `<Vendor><Thing>Provider` | `AviationWeatherAirportProvider` |
| Provider DTO | `<Vendor><Thing>Dto` (package-private) | `AviationWeatherAirportDto` |
| Mapper | `<Vendor><Thing>Mapper` (final, static methods) | `AviationWeatherAirportMapper` |
| Properties | `<Vendor>Properties` record, prefix `aviation.provider.<vendor>` | `AviationWeatherProperties` |
| Response | `<Thing>Response` record with `static from(domain)` | `AirportResponse` |
| Domain failure | `<Cause>Exception` extending `RuntimeException` or `UpstreamException` | `UpstreamTimeoutException` |
| Resilience instance | lowerCamel vendor name, shared by retry and breaker | `aviationWeather` |
| Tests | `<Class>Test` (unit), `<Feature>IntegrationTest extends IntegrationTestSupport` (Spring context + WireMock) | `AirportLookupIntegrationTest` |
| Scenario catalogue | `docs/scenarios.md`, one row per behaviour, referencing the proving test | `R8 ... IT.circuitClosesAgainOnceUpstreamRecovers` |

## Configuration

- Typed `@ConfigurationProperties` records with `@Validated` constraints; no `@Value`.
- Every tunable (timeouts, attempts, TTLs) lives in `application.yml` with a `test` override
  in `src/test/resources/application-test.yml` using millisecond timings.
- Class names in Resilience4j `retry-exceptions` / `ignore-exceptions` / `record-exceptions`
  lists must be fully qualified; update them when moving or renaming exceptions.

## Commits

Conventional commits, imperative, scoped when useful:
`feat(adapter): ...`, `fix(web): ...`, `test: ...`, `docs: ...`, `chore: ...`, `refactor: ...`.
One logical change per commit; the build (including `spotlessCheck`) must pass at every commit.
