# MovieMux - API

## Introduction

This is a personal project aimed at creating a comprehensive movie rating portal. Whether you're a movie buff or simply
looking for a way to discover new films.

Inspired by IMDB and TMDB.

## Technologies

```
Java 25, Spring Boot, JPA, Spring Security, Lombok, Spring Validation, Actuator
```

### Link

[MovieMux Frontend](https://github.com/HenriqueMendesCoelho/moviemux)

This product uses the TMDB API but is not endorsed or certified by TMDB.

## Verification

Java 25 and Maven are required. No external API credentials are needed.

- Fast unit, serialization, HTTP/security and architecture tests: `./mvnw test`.
- Full suite: `./mvnw clean verify`, then `python3 scripts/check-test-reports.py`.
- Full verification requires a disposable PostgreSQL database, never production.
  Set `TEST_DATABASE_URL`, `TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD`.
  The default test URL deliberately points to a closed loopback port.
- External HTTP contracts use local stubs. Email, Discord and scheduled jobs are
  disabled by default in the test profile. Tests must not use real user data or credentials.
- Unit reports: `target/surefire-reports`; integration reports:
  `target/failsafe-reports`; coverage: `target/site/jacoco` and
  `target/site/jacoco-it`. A skipped build is not test verification.

The CI verification job runs against PostgreSQL 14 with synthetic data. This is
not evidence of compatibility with an uninspected production schema or of a
successful deployment. Container packaging does not replace the verification job.

Coverage gates are measured separately by module and layer. The initial unit
ratchet is in `scripts/coverage-ratchet.json`; untested layers remain visible in
reports and are not excluded from coverage. Run `python3 scripts/check-coverage.py`
after tests. Combined unit/integration coverage is in `target/site/jacoco-all`.

Selective mutation testing: `./mvnw -Pmutation test org.pitest:pitest-maven:mutationCoverage`.
It currently protects wishlist-creation decisions; the 100% gate applies only to
that small selection, not to the whole application. Reports are in
`target/pit-reports`. CI also tests its collection and coverage gates with
`python3 scripts/test_quality_gates.py`.

## Backend boundaries

Each business module owns its domain models, commands, persistence ports and
single-operation use cases under `domain`. HTTP DTOs and mappers stay in
`adapter/controller`; Spring Data repositories, JPA entities and persistence
mapping stay in `adapter/repository`. `application/spring` contains technical
wiring, scheduled jobs and cache decorators. Cross-module composition is kept
outside the business kernels and uses declared domain contracts, rather than
another module's entities, controllers or repository implementations.

User, movie and wishlist persistence models are detached from their domain
models. Cross-module ownership uses IDs and public identity projections. The
architecture tests check both core dependencies and the complete module graph,
including adapters and entities, without a temporary dependency allowlist.

Multi-write operations use local database transactions. Email and socket
notifications are dispatched after a successful commit. Mail delivery runs on a
bounded asynchronous executor; a saturated queue or transport failure is recorded
without retrying or undoing the committed operation. There is no durable outbox,
and pending mail can be lost when the process stops. Socket delivery remains
synchronous after commit. Password-reset state is saved before its email is
queued; a successful reset by key also queues a password-updated notification.
Discord remains a synchronous external effect and cannot be rolled back by a
database transaction.

Copy `.env.example` to configure development with your own values. Mail uses the
separate `MOVIEMUX_MAIL_URL` provider at `/api/mail/send`, with template identifiers
and metadata rather than the KIT bearer token. KIT, mail, Discord and socket use
independently configured synchronous HTTP clients; tests verify their contracts
against local stubs. Never commit real environment files or credentials.

## Database migrations

Flyway runs during application startup before Hibernate schema validation.
Hibernate does not update the schema, and automatic Flyway baselining is disabled.
An existing database without migration history must not be adopted implicitly.
Inspect and compare its actual schema, including objects not represented by JPA,
before choosing an explicit baseline and rollout procedure.

V1 preserves the historical Hibernate DDL. Three cross-module foreign keys are
owned by SQL migrations rather than JPA associations. The migration tests compare
the complete materialized PostgreSQL catalog against an immutable historical DDL
fixture, including all 13 tables and 12 foreign keys. Hibernate validation and
Flyway checksums alone do not detect every missing constraint. Keep the historical
fixture and V1 immutable; make subsequent schema changes in new migrations.

The local suite covers fresh databases, synthetic legacy adoption, migration
failures, schema drift and concurrent migration startup. It is not a substitute
for inspecting the deployment database, checking PostgreSQL compatibility,
rehearsing restoration, or reviewing application-version compatibility and locks.
No test command should use production connection details.

## Test maintenance

Use synthetic fixtures, a fixed clock where time matters, and loopback HTTP stubs.
The default test profile disables external effects and jobs. Tests that explicitly
exercise startup scheduling must still isolate the database and stub integrations.
Real HTTP workflow tests exercise authentication and database-backed operations;
legacy defects documented by characterization tests are not endorsements of those
behaviors or evidence that they are safe to release.

A failing or flaky required test blocks verification. Investigate and fix its
cause instead of hiding it with automatic retries, broad exclusions, disabled
tests or reduced coverage thresholds. PostgreSQL concurrency tests should control
interleavings with bounded synchronization rather than timing assumptions. Keep
unit, integration, coverage and selective mutation results distinct when reviewing
changes; a green packaging build or a global percentage cannot replace a missing
security or rollback scenario.

### Wishlist mutation safety

Wishlist writes require the owner's UUID value; making a list shareable does not
allow other users to edit it. An update must address the same list in its URL and
body, and nested membership identities are derived from that authorized list.
Updates still replace the requested collection and preserve its submitted order.

Creation serializes the ten-list quota per owner with a transaction-scoped
PostgreSQL advisory lock. Add/update operations lock the wishlist row before
reading and validating the resulting maximum of 100 items. These locks require
an existing transaction and remain held until commit or rollback. They protect
concurrent operations through this application, not direct SQL writes or older
application versions that do not follow the same locking protocol. No schema
migration or change to historical V1 is required for this correction.
