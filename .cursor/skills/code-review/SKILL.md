---
name: code-review
description: Review a diff, branch or pull request of aviation-api-wrapper against its architecture rules, resilience policy, error contract, test requirements and coding conventions, producing severity-ranked findings. Use when asked to review code, review a PR, check a change before merge, or act as a reviewer for this repository.
---

# Code review

Load [project-context](../project-context/SKILL.md) and
[conventions](../project-context/conventions.md) before reading the diff. Review against
them, not against generic preferences. The service is judged on scalability, resilience,
extensibility and observability; a finding matters in proportion to how it affects those.

## Procedure

1. Identify the scope: `git diff <base>...HEAD` (or the files the user points at). Read every
   changed file in full, not only hunks.
2. Classify the change: endpoint, integration, resilience/config, tests, docs, build.
   Apply the matching section below plus "Always".
3. Run, do not assume: `./gradlew spotlessCheck test`. Quote failures verbatim.
4. Write findings using the output format. Cite `file:line`. Propose the concrete fix.

## Always

- **Layering**: `domain` imports nothing from Spring/Jackson/R4j/HTTP; adapters do not leak
  `ResourceAccessException`, status codes or DTOs upward; no single-implementation interfaces
  except ports; no pass-through classes.
- **Statelessness**: no request-scoped or instance state beyond the cache.
- **Conventions**: no comments/Javadoc (except a one-line workaround note), `final` parameters,
  records for data, constructor injection, SLF4J placeholders, Spotless-clean.
- **Security**: no secrets in code or YAML, no user input in log lines without sanitising,
  no stack traces or provider bodies in `ProblemDetail.detail`.
- **Docs**: README and `project-context/SKILL.md` updated when the contract, policy or
  architecture changed.

## Endpoint changes

- Input validated by a domain value object built in the controller; invalid input provably
  never reaches the provider (test with `verify(0)`).
- Response is a `record` with `@Schema` examples and `static from(domain)`; no domain or
  provider types in the payload.
- OpenAPI annotations on the `<Resource>Api` interface, not on the controller; only
  operation-specific statuses declared there (shared 400/5xx come from `ProblemDocumentation`).
- Every reachable status mapped in `GlobalExceptionHandler` through the `problem(...)` helper
  with a kebab-case slug, and a row added to `docs/scenarios.md`.
- Path under `/api/v1`, plural nouns, no verbs.

## Integration changes

- Exception translation covers every row: 204/empty, undecodable 2xx, 429 with `Retry-After`,
  5xx, other 4xx, timeout vs other I/O, other `RestClientException`.
- `@Retry` and `@CircuitBreaker` on the port method, shared instance name, YAML lists use fully
  qualified class names and include `CallNotPermittedException` in retry `ignore-exceptions`.
- Not-found ignored by the breaker; 429 and bad-response not retried.
- Per-attempt timeouts set on the `RestClient`; worst-case latency documented.
- `User-Agent` set; base URL configurable (tests use WireMock).
- Real payload fixture present; units verified against it.

## Resilience / configuration changes

- Any change to attempts, backoff, window, `minimumNumberOfCalls` or timeouts is reflected in
  the attempt-count assertions of the integration tests and in the README latency estimate.
- Health indicator still cannot fail the aggregate; container healthcheck still liveness-only.
- Cache: successes only, fixed cache names, `recordStats` preserved.

## Test changes

- Integration tests reset WireMock, breaker and cache in `@BeforeEach`.
- Assertions are on HTTP contract and upstream call counts, not on logs or messages.
- No `Thread.sleep`, no unseeded randomness, no order dependence.
- New behaviour has a failing-then-passing test; deleted tests are justified in the PR.

## Output format

```markdown
## Summary
One paragraph: what the change does and whether it is mergeable.

## Findings
### Blocker
- `path/File.java:42` - <what is wrong> -> <concrete fix>
### Should fix
- ...
### Nit
- ...

## Verified
- `./gradlew spotlessCheck test`: <result, counts>
- <any manual check performed>
```

Severity:

- **Blocker**: breaks layering, leaks transport types, weakens resilience (retries 429, counts
  not-found as failure, removes a reset in tests), changes the error contract without docs/tests,
  fails build or Spotless.
- **Should fix**: missing scenario from the coverage table, undocumented config change,
  convention violations that reduce readability, missing OpenAPI status.
- **Nit**: naming, ordering, wording.

Do not pad the review: if a section has no findings, omit it. Praise only when it points at a
pattern others should copy.
