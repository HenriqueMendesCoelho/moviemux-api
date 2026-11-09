# MovieMux - API

## Introduction

This is a personal project aimed at creating a comprehensive movie rating portal. Whether you're a movie buff or simply
looking for a way to discover new films.

Inspired by IMDB and TMDB.

## Technologies

```
Java 21, Spring Boot, JPA, Spring Security, Lombok, Spring Validation, Actuator
```

### Link

[MovieMux Frontend](https://github.com/HenriqueMendesCoelho/moviemux)

This product uses the TMDB API but is not endorsed or certified by TMDB.

## Verification

Java 21 and Maven are required. No external API credentials are needed.

- Fast unit, serialization, HTTP/security and architecture tests: `./mvnw test`.
- Full suite: `./mvnw clean verify`, then `python3 scripts/check-test-reports.py`.
- Full verification requires a disposable PostgreSQL database, never production.
  Set `TEST_DATABASE_URL`, `TEST_DATABASE_USER` and `TEST_DATABASE_PASSWORD`.
  The default test URL deliberately points to a closed loopback port.
- External HTTP contracts use local stubs. Email, Discord and scheduled jobs are
  disabled by the test profile. Tests must not use real user data or credentials.
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
