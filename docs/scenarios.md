# Expected scenarios

Traceability between the assignment's evaluation criteria (correctness, scalability,
resilience, extensibility, observability), the behaviour the service commits to, and the
automated test that proves it. `IT` = `AirportLookupIntegrationTest`,
`OPS` = `OperationalEndpointsIntegrationTest`; unit tests are named in full.

## Correctness

| # | Given | When | Then | Test |
|---|---|---|---|---|
| C1 | provider knows the airport | `GET /api/v1/airports/KJFK` | 200 JSON with icao, iata, name, state, country, coordinates, elevation in metres, runways, `source` | `IT.returnsMappedAirportForKnownIcao` |
| C2 | any request | code in lower/mixed case | normalised to upper case before validation, cache and provider | `IcaoCodeTest`, `IT.lowercaseAndUppercaseCodesShareOneCacheEntry` |
| C3 | code is not exactly four letters A-Z | request | 400 `invalid-icao-code`; provider **not** called | `IcaoCodeTest`, `IT.invalidIcaoIsRejectedWithoutCallingUpstream` |
| C4 | provider answers 204 or an empty list | request | 404 `airport-not-found`; no retry | `IT.upstreamNoContentBecomesNotFoundAndIsNotRetried`, `AviationWeatherAirportMapperTest` |
| C5 | provider payload is unusable (null element, identifier missing or for another airport, blank name, missing/non-finite/out-of-range coordinates) | request | 502 `upstream-bad-response`, no retry, nothing cached | `AviationWeatherAirportMapperTest`, `IT.unusableRecordsBecomeBadGatewayWithoutRetry`, `IT.unusableRecordIsNotCachedSoTheNextRequestAsksUpstreamAgain` |
| C6 | caller sends `X-Correlation-Id` | request | same id echoed in header, body and logs; generated when absent | `IT.echoesIncomingCorrelationIdAndGeneratesOneWhenAbsent` |
| C7 | route, method or `Accept` the API does not support | request | 404 / 405 / 406 Problem Details, never a bare 500 | `IT.unknownRouteIsNotFoundProblemNotInternalError`, `IT.unsupportedMethodIsMethodNotAllowedProblem`, `IT.unacceptableMediaTypeIsNotAcceptableProblem` |

## Resilience

| # | Given | When | Then | Test |
|---|---|---|---|---|
| R1 | provider answers 5xx then recovers | request | up to 3 attempts with exponential backoff; 200 on the first success | `IT.retriesTransientServerErrorsAndSucceeds` |
| R2 | provider keeps answering 5xx | request | 503 `upstream-unavailable` after exactly 3 attempts | `IT.exhaustedRetriesOnServerErrorBecomeServiceUnavailable` |
| R3 | provider slower than the read timeout | request | 504 `upstream-timeout` after 3 attempts; each attempt bounded by its own timeout | `IT.upstreamTimeoutBecomesGatewayTimeoutAfterRetries` |
| R4 | provider answers 429 | request | 503 `upstream-rate-limited`, `Retry-After` forwarded, **no** retry (would worsen throttling) | `IT.upstreamRateLimitIsNotRetriedAndRetryAfterIsForwarded` |
| R5 | provider answers an unexpected 4xx | request | 502 `upstream-bad-response`, no retry | `IT.unexpectedClientErrorFromUpstreamBecomesBadGatewayWithoutRetry` |
| R6 | provider answers 200 with a body we cannot decode | request | 502 `upstream-bad-response`, no retry | `IT.malformedUpstreamBodyBecomesBadGateway` |
| R7 | 5 attempts in a row failed (50 % of a 10-call window) | next request | breaker OPEN: 503 `upstream-circuit-open` **without** calling the provider | `IT.circuitOpensAfterRepeatedFailuresAndShortCircuitsSubsequentCalls` |
| R8 | breaker OPEN and the wait duration elapsed | provider healthy again | HALF_OPEN lets 3 probes through; breaker CLOSED after they succeed | `IT.circuitClosesAgainOnceUpstreamRecovers` |
| R9 | many not-found answers | requests | not-found is a valid answer: it never opens the breaker | `IT.notFoundDoesNotCountTowardsCircuitBreakerFailures` |
| R10 | breaker OPEN | `/actuator/health` | overall status stays UP (the pod must not be restarted), breaker component reports `CIRCUIT_OPEN` | `OPS.healthStaysUpWhileCircuitIsOpenButReportsIt` |

## Scalability

| # | Given | When | Then | Test |
|---|---|---|---|---|
| S1 | airport already looked up in the last 10 min | repeated request (any case) | served from cache, provider not called | `IT.lowercaseAndUppercaseCodesShareOneCacheEntry` |
| S2 | many concurrent requests for the same code on a cache miss | burst | provider called **once** (`@Cacheable(sync = true)`), everybody gets the result | `IT.concurrentMissesForTheSameCodeCallUpstreamOnce` |
| S3 | failure of any kind (5xx after retries, unusable payload) | request | nothing cached, so a later success still reaches the provider | `IT.failedLookupsAreNotCached`, `IT.unusableRecordIsNotCachedSoTheNextRequestAsksUpstreamAgain` |
| S4 | instance scaled horizontally | - | no shared state besides the local cache; any replica can serve any request | by design (see README, Architecture) |
| S5 | slow provider, many concurrent callers | burst | requests park on virtual threads; no platform thread pool to exhaust | `spring.threads.virtual.enabled=true` (README, Concurrency model) |
| S6 | more concurrent distinct lookups than the bulkhead allows | burst | excess requests fail fast with 503 `upstream-saturated`, provider never sees them, breaker unaffected | `IT.concurrentCallsBeyondTheBulkheadAreRejectedWithoutQueueingOnUpstream` |
| S7 | this instance has spent the current cycle (10 calls / 8 s) | another distinct lookup | 503 `outbound-rate-limited` with `Retry-After`, provider not called, breaker unaffected; the next cycle grants only that small quota, not the previous one as well; each retry attempt spends one permit | `IT.outboundBudgetRejectsFurtherDistinctLookupsWithoutCallingUpstream`, `IT.refreshGrantsOnlyTheNextSmallQuota`, `IT.eachRetryAttemptConsumesOneOutboundPermit` |

## Extensibility

| # | Given | When | Then | Test |
|---|---|---|---|---|
| E1 | a new data provider | implement `AirportProvider` | domain, service, cache, web contract untouched | by design; see `.cursor/skills/create-external-integration` |
| E2 | a new endpoint | add method to `AirportApi` + controller | shared error responses documented automatically (`ProblemDocumentation`) | `OPS.openApiDocumentCarriesContractFromInterfaceAndSharedProblemResponses` |
| E3 | a new `UpstreamException` subtype without explicit HTTP mapping | thrown | 502 `upstream-error` safety net, never a 500 | handler fallback (`GlobalExceptionHandler.upstreamFallback`) |

## Observability

| # | Given | When | Then | Test |
|---|---|---|---|---|
| O1 | running instance | `/actuator/health`, `/liveness`, `/readiness` | 200, probes UP, breaker state visible | `OPS.healthExposesProbesAndCircuitBreakerState` |
| O2 | traffic served | `/actuator/prometheus` | `cache_*`, `resilience4j_*`, `http_client_requests_*`, `http_server_requests_*` present | `OPS.prometheusExposesCacheResilienceAndHttpClientMetrics` |
| O3 | any request | logs | every line carries `cid=<correlationId>`; breaker transitions and retries logged at WARN/INFO | `IT.echoesIncomingCorrelationIdAndGeneratesOneWhenAbsent`, `ResilienceEventLogging` |
| O4 | API consumer | `/swagger-ui.html`, `/v3/api-docs` | contract with parameters, examples and every error status | `OPS.openApiDocumentCarriesContractFromInterfaceAndSharedProblemResponses` |

## Known gaps (deliberate)

- The outbound rate limit is a fixed cycle of 10 calls / 8 s **per instance**, chosen so a
  refill at the boundary cannot exceed the provider's 100 requests/minute. It is not a sliding
  window and it is not shared: two replicas each have their own counter. A shared budget
  belongs in a gateway or a distributed limiter. The bulkhead is still only a concurrency cap.
- No stale cache. A hit during an outage is served; a miss or an expired entry is 503/504.
  Serving a stale airport would require the response to carry the record's age and a maximum
  acceptable age. A second provider is a different product: coverage and contracts differ.
  `upstream-error` is a generic handler mapping, not a fallback.
- No global per-request deadline: worst case is about 9.6 s (3 attempts x (1 s connect + 2 s read) + backoff).
- Cache, breaker, bulkhead and rate limiter are per instance; replicas warm up independently.
- Actuator endpoints are unauthenticated. Production must restrict them by network, gateway or
  authentication. A separate management port is not, by itself, that restriction.
