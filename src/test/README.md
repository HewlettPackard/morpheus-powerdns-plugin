# Unit Tests

This directory contains fast, fully offline unit tests written with [Spock](https://spockframework.org/)
(Groovy's Spock testing framework, running on JUnit 5 under the hood).

## What's covered here vs. `src/functionalTest`

This plugin sits between two systems it doesn't own: the Morpheus plugin runtime (`MorpheusContext`,
`HttpApiClient`, model classes like `AccountIntegration`/`NetworkDomainRecord`) and a real Power DNS server.
Because of that, tests split into two layers:

* **`src/test` (this directory)** - mocks everything outside this plugin (`HttpApiClient`, `MorpheusContext`,
  `PowerDnsApiClient`) and verifies *this plugin's own logic*: the shape of the HTTP requests/payloads it
  builds, how it parses responses, and the decisions it makes (e.g. "does this record already exist,
  should I create it or return an error"). These tests run in milliseconds, require no network access or
  external services, and run as part of `./gradlew test`/`./gradlew check`/`./gradlew build`.
* **`src/functionalTest`** - makes real HTTP calls to a real, reachable Power DNS instance to verify the
  plugin's assumptions about the Power DNS API itself are correct. See `src/functionalTest/README.md`.

In short: unit tests answer "does `PowerDnsApiClient`/`PowerDnsProvider` do the right thing given a
response?"; functional tests answer "does Power DNS actually respond the way we assume it does?"

## Structure

* `PowerDnsApiClientSpec.groovy` - tests `PowerDnsApiClient`, the class that owns all raw Power DNS HTTP
  request/response/payload logic (`createRecord`, `deleteRecord`, `doesRecordExist`, `getRecordCreateBody`,
  etc). The `HttpApiClient` it wraps is replaced with a Spock `Mock(HttpApiClient)` injected via the
  constructor (`new PowerDnsApiClient(integration, mockClient)`), so no real HTTP calls are made. Assertions
  use Spock's interaction verification (e.g. `1 * client.callJsonApi(...)`) with closure constraints to
  inspect the request URL, headers, and JSON body that would have been sent.
* `PowerDnsProviderSpec.groovy` - tests `PowerDnsProvider`'s orchestration logic (`createRecord`,
  `deleteRecord`) without touching the network at all. `PowerDnsProvider` exposes a protected
  `createApiClient(AccountIntegration)` factory method specifically so tests can substitute a mock/stub
  `PowerDnsApiClient` via a Spock `Spy` (`provider.createApiClient(_) >> mockApiClient`). This lets these
  tests verify behavior like "if the record already exists, don't call create" or "map a failed create
  response into an error `ServiceResponse`" purely at the orchestration level.

## Why mocking works here

* `HttpApiClient` is a concrete, non-final class with a no-arg constructor, so Spock can mock it directly
  (byte-buddy backed - see the `testImplementation 'net.bytebuddy:byte-buddy'` / `'org.objenesis:objenesis'`
  dependencies in `build.gradle`).
* `MorpheusContext` is an interface, so it's trivially mockable.
* `PowerDnsApiClient` and `PowerDnsProvider` both accept dependencies (an `HttpApiClient`, or a factory
  method for constructing a `PowerDnsApiClient`) that can be swapped out in tests - this is the seam that
  makes orchestration logic testable without a real integration configured.

## Running

```
./gradlew test
```

Test reports are written to `build/reports/tests/test/index.html`.

## Adding new tests

* Prefer adding new `*Spec.groovy` files (or new `def "..."` feature methods within existing specs) here for
  any new pure-logic behavior in `PowerDnsApiClient`/`PowerDnsProvider`.
* If you need to verify actual behavior against a real Power DNS server (e.g. confirming payload shape
  assumptions, testing against a specific Power DNS version), add that to `src/functionalTest` instead -
  keep this directory limited to fast, offline, deterministic tests.
