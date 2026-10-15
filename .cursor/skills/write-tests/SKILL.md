---
name: write-tests
description: Write or extend unit and integration tests for aviation-api-wrapper using JUnit 6, AssertJ, Mockito and WireMock, following the project's test layout, state-reset rules and resilience scenario patterns. Use when asked to add tests, cover a scenario, test an endpoint or provider, fix a flaky test or raise coverage.
---

# Write tests

Read [project-context](../project-context/SKILL.md) first. Tests document the contract:
HTTP status, problem `type`, number of upstream calls, breaker state.

## Which level

| Change | Test |
|---|---|
| Value object, mapper, pure function | Unit, no Spring (`<Class>Test`) |
| Service orchestration | Unit with Mockito (`@ExtendWith(MockitoExtension.class)`) |
| Anything observable over HTTP: new endpoint, error mapping, provider behaviour, resilience, cache | Integration (`<Feature>IntegrationTest`, `@SpringBootTest(RANDOM_PORT)` + WireMock) |

Do not use `@WebMvcTest` slices for error-contract tests: the resilience aspects, cache and
exception-advice ordering only exist in the full context.

## Unit test rules

- Name by behaviour: `rejectsAnythingThatIsNotFourLetters`, `mapsAllFieldsTrimmingName...`.
- One concept per test; `@ParameterizedTest` + `@ValueSource`/`@NullAndEmptySource` for input classes.
- AssertJ only (`assertThat`, `assertThatThrownBy(...).isInstanceOf(...).hasMessageContaining(...)`).
- Build fixtures inline; no shared mutable fixtures, no `@BeforeEach` state unless reused by every test.

## Integration test pattern

Extend `IntegrationTestSupport` (`src/test/java/com/msvieira/aviation`). It owns:

- a single WireMock server started once per JVM on a dynamic port (static initialiser, never
  stopped per class, so the cached Spring context keeps pointing at the right port);
- `@DynamicPropertySource` wiring `aviation.provider.aviationweather.base-url` to it;
- `@BeforeEach resetState()` resetting WireMock, the breaker and the cache;
- helpers `stubAirport(icao, response)`, `stubAnyAirport(response)`, `call(path)`,
  `assertProblem(res, slug)`, `fixture()` and the `Response` record.

```java
class <Feature>IntegrationTest extends IntegrationTestSupport {

    @Test
    void describesBehaviourNotImplementation() {
        stubAirport("KJFK", okJson(fixture()));

        Response res = call("/api/v1/airports/KJFK");

        assertThat(res.status()).isEqualTo(200);
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(UPSTREAM_PATH)));
    }
}
```

Existing classes: `AirportLookupIntegrationTest` (functional + resilience scenarios) and
`OperationalEndpointsIntegrationTest` (health, Prometheus, OpenAPI). Put new scenarios where
they belong; create a third class only for a new feature area. Every scenario gets a row in
`docs/scenarios.md`.

Mandatory rules:

- **Reset all three** (WireMock, breaker, cache) before every scenario (the base class does it).
  A test that depends on another test's state is a bug. If you need a second provider, add its
  base-url to the base class, do not start a second WireMock.
- Call the API through a real HTTP client (`RestClient.create("http://localhost:" + port)` with
  `.exchange(...)`) and read status, headers and body; never call the controller directly.
- Assert errors with the `assertProblem(res, "<type-slug>")` helper: content type
  `application/problem+json`, `type`, `status`, non-blank `title`/`detail`, and `correlationId`
  equal to the `X-Correlation-Id` response header.
- Assert **how many times** the provider was called with `wireMock.verify(n, getRequestedFor(...))`.
  Every resilience assertion is a call-count assertion: not-found -> 1, retry success -> 3,
  429 -> 1, invalid input -> 0, open breaker -> unchanged.
- Count breaker failures in **attempts**, not requests: with `maxAttempts=3` and
  `minimumNumberOfCalls=5`, two failing requests produce five attempts and open the circuit.
- Use `withFixedDelay(ms)` larger than the test read timeout for timeout scenarios;
  use WireMock scenarios (`inScenario/whenScenarioStateIs/willSetStateTo`) for flaky sequences.
- No `Thread.sleep` except inside a bounded poll (see `awaitStatus` in
  `AirportLookupIntegrationTest`, used for the OPEN -> HALF_OPEN -> CLOSED recovery); the `test`
  profile already shrinks all timings (`wait-duration-in-open-state: 1s`).
- Concurrency scenarios use `Executors.newVirtualThreadPerTaskExecutor()` + `invokeAll` and
  assert the provider call count (stampede: 20 clients -> `verify(1, ...)`).

## Fixtures

- Real payloads captured from the provider live in `src/test/resources/wiremock/<key>.json`.
  Capture with `curl ... | python3 -m json.tool` and do not hand-edit fields.
- Load with the `fixture()` helper. Tests that stub **any** ICAO (breaker recovery, bulkhead)
  must echo the requested code in the body (`fixtureForRequestedCode()`, WireMock
  `{{request.query.ids}}`). Serving KJFK for KLAX is a 502 after the identity check.

## Coverage expectations for a change

| Change | Required scenarios |
|---|---|
| New endpoint | 200 happy path, each documented 4xx, invalid input with `verify(0)` |
| New provider / status handling | every row of the exception-translation table in create-external-integration |
| New domain exception | handler mapping (status, type slug, `Retry-After` if any) |
| Cache change | second identical call -> `verify(1)`; different keys -> separate calls |
| Resilience config change | adjust attempt counts in the breaker and retry tests, keep them exact |

## Running

```bash
./gradlew test
./gradlew test --tests '*IcaoCodeTest'
./gradlew test --tests '*IntegrationTest' -i      # see application logs for a scenario
```

Failures: open `build/reports/tests/test/index.html`; the integration log shows retry attempts
and breaker transitions per correlation id.

## Anti-patterns

- `Thread.sleep`, random data without a seed, order-dependent tests.
- Mocking `RestClient` or the provider inside integration tests (use WireMock).
- Asserting on log output or exception messages instead of HTTP contract and call counts.
- Stubbing with `anyUrl()` when the test should prove the query parameters sent.
