# Functional Tests

This directory contains tests that exercise `PowerDnsApiClient` against a **real, reachable Power DNS
instance** - no mocks. They verify the plugin's assumptions about the actual Power DNS HTTP API (request/
response shapes, auth, record lifecycle) are correct, which the mocked unit tests in `src/test` cannot do.

These tests are **not** run as part of `./gradlew test`, `./gradlew check`, or `./gradlew build`. They run
only via the dedicated `functionalTest` Gradle task, and are automatically skipped (not failed) when no
Power DNS instance is configured, so it's always safe to run `./gradlew build`/`check` without one.

## Prerequisites: a reachable Power DNS instance

Any Power DNS authoritative server with the built-in HTTP API enabled will work. The easiest way to get one
locally is via Docker, e.g.:

```bash
docker run -d --name pdns-functional-test \
  -p 8081:8081 -p 53:53/udp -p 53:53/tcp \
  -e PDNS_api=yes \
  -e PDNS_api_key=changeme \
  -e PDNS_webserver=yes \
  -e PDNS_webserver_address=0.0.0.0 \
  -e PDNS_webserver_allow_from=0.0.0.0/0 \
  psitrax/powerdns
```

Then create a test zone to run the tests against (defaults to `example.com.` - see below):

```bash
curl -s -H "X-API-Key: changeme" -H "Content-Type: application/json" \
  -X POST http://localhost:8081/api/v1/servers/localhost/zones \
  -d '{"name": "example.com.", "kind": "Native", "nameservers": ["ns1.example.com."]}'
```

## Configuration (environment variables)

| Variable                       | Required | Description                                                          |
|---------------------------------|----------|------------------------------------------------------------------------|
| `POWERDNS_TEST_URL`              | Yes      | Base URL of the Power DNS API, e.g. `http://localhost:8081`            |
| `POWERDNS_TEST_API_KEY`          | Yes      | The Power DNS API key (`X-API-Key` header value)                       |
| `POWERDNS_TEST_ZONE`             | No       | Zone name to run tests against (default: `example.com.`). Must already exist on the server. |
| `POWERDNS_TEST_SERVICE_VERSION`  | No       | Power DNS API version to emulate (default: `4`; use `3` for the legacy payload shape) |

If `POWERDNS_TEST_URL` or `POWERDNS_TEST_API_KEY` are not set, all functional tests are skipped automatically
(via a Spock `@IgnoreIf`) rather than failing the build.

## Running

```bash
export POWERDNS_TEST_URL=http://localhost:8081
export POWERDNS_TEST_API_KEY=changeme
export POWERDNS_TEST_ZONE=example.com.

./gradlew functionalTest
```

Test reports are written to `build/reports/tests/functionalTest/index.html`.

## What's covered

* `PowerDnsApiClientFunctionalSpec.groovy`:
  * `listZones` - confirms the configured test zone is visible via the live API.
  * A full `createRecord` -> `doesRecordExist` -> `deleteRecord` round-trip using a throwaway `A` record
    (`functest.<zone>`), verifying the record doesn't exist beforehand, is created and detectable
    immediately afterward (this is what closes the MORPH-12570 duplicate-record race condition - the
    live `doesRecordExist` check must see records right after they're created, not just after a sync), and
    is fully removed afterward.

## Adding new functional tests

* Add new `*FunctionalSpec.groovy` files (or feature methods) here for anything that needs to validate real
  Power DNS API behavior (e.g. testing against a different Power DNS version, verifying a new record type).
* Keep tests idempotent/self-cleaning: any record created by a test should be deleted by the end of that
  test so re-running the suite doesn't leave stale state on the test zone.
* Keep pure-logic tests (mocking `HttpApiClient`) in `src/test` instead - only add tests here when they
  genuinely require a live Power DNS server.
