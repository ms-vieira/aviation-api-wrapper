---
name: create-endpoint
description: Add a new HTTP endpoint (REST operation) to aviation-api-wrapper following its hexagonal layering, RFC 9457 error contract, OpenAPI documentation and test requirements. Use when asked to create, add or expose an endpoint, route, resource or API operation.
---

# Create an endpoint

Read [project-context](../project-context/SKILL.md) first. An endpoint is a thin inbound
adapter: validate at the edge, call one application method, map the result to a response record.

## Checklist

```
- [ ] 1. Domain: value objects / model / exceptions the operation needs
- [ ] 2. Application: one public method on a service (or a new service)
- [ ] 3. Web: controller method + response record + OpenAPI annotations
- [ ] 4. Errors: new exceptions mapped in GlobalExceptionHandler and documented
- [ ] 5. Tests: unit for domain/service, integration through HTTP (see write-tests)
- [ ] 6. README: API section updated; ./gradlew spotlessApply build passes
```

## Step 1: Domain first

- Inputs become value objects that validate in their constructor (`IcaoCode` is the model).
  Throw a dedicated `*Exception` from `domain.exception`; the controller never validates by hand.
- If the operation needs new data from a provider, extend the port `AirportProvider` (or add a
  new port) and implement it in the adapter. Follow
  [create-external-integration](../create-external-integration/SKILL.md).

## Step 2: Application

- One method per use case, named after the business operation (`getByIcao`, not `handle`).
- Decide caching here. `@Cacheable` only for idempotent reads whose key is the normalised
  input; declare the cache name as a public constant on the service and register it in
  `CacheConfig` (names are fixed so metrics bind at startup).
- No HTTP, no DTOs, no `ResponseEntity` in this layer.

## Step 3: Web

Documentation and behaviour are split in two files. The OpenAPI contract is an interface:

```java
@Tag(name = "<Resources>", description = "...")
public interface <Resource>Api {

    @Operation(summary = "...", description = "...")
    @ApiResponse(responseCode = "200", description = "...")
    @ApiResponse(responseCode = "404", description = "...", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(ref = ProblemDocumentation.SCHEMA_REF)))
    <Resource>Response getById(@Parameter(description = "...", example = "...") String id);
}
```

The controller implements it and keeps only MVC routing:

```java
@RestController
@RequestMapping(path = "/api/v1/<resources>", produces = MediaType.APPLICATION_JSON_VALUE)
public class <Resource>Controller implements <Resource>Api {

    private final <Resource>Service service;

    public <Resource>Controller(final <Resource>Service service) {
        this.service = service;
    }

    @Override
    @GetMapping("/{id}")
    public <Resource>Response getById(@PathVariable final String id) {
        return <Resource>Response.from(service.getById(<ValueObject>.of(id)));
    }
}
```

- Versioned path under `/api/v1`. Plural resource nouns. No verbs in paths.
- Response is a `record` in `adapter.web` with `@JsonInclude(NON_NULL)`, `@Schema` examples on
  every field and a `static from(domain)` factory. Never expose domain or provider types.
- Document on the interface only what is specific to the operation (200 and e.g. 404).
  400/500/502/503/504 with the `Problem` schema are added to every operation by
  `ProblemDocumentation` (springdoc `OperationCustomizer`); do not repeat them.
- Do not add request-scoped state; the service must stay stateless.

## Step 4: Errors

- New domain exception -> new `@ExceptionHandler` in `GlobalExceptionHandler` using the
  `problem(status, slug, title, detail)` helper. Slug is kebab-case and becomes
  `urn:aviation-api-wrapper:problem:<slug>`.
- Never leak stack traces or provider payloads in `detail`; log them instead.
- Add the row to the error table in `README.md` and to `project-context/SKILL.md`.

## Step 5: Tests (mandatory)

- Unit: value object validation, service delegation.
- Integration (extend `IntegrationTestSupport`): happy path with a realistic fixture,
  each error status, and `verify(0, ...)` proving invalid input never reaches the provider.
  See [write-tests](../write-tests/SKILL.md).
- Add the scenario rows to `docs/scenarios.md` (Given / When / Then / Test).

## Step 6: Finish

```bash
./gradlew spotlessApply build
```

Update `README.md` (API section, error table) and commit as `feat(web): ...`.

## Anti-patterns

- Validation with `@Pattern` on the controller parameter instead of a value object (bypasses
  the domain rule and produces a different 400 shape).
- Returning `ResponseEntity<Map<String, Object>>` or domain objects directly.
- Catching exceptions in the controller; mapping belongs to `GlobalExceptionHandler`.
- A new `*UseCase` interface with one implementation.
