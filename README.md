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
owned by SQL migrations rather than JPA associations. The V1 migration tests compare
the complete materialized PostgreSQL catalog against an immutable historical DDL
fixture, including all 13 tables and 12 foreign keys. Subsequent migration tests
separately verify that the historical tables and data are preserved. Hibernate validation and
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

## Ranking access

`GET /rankings/{rankingId}/access` (under the configured API context path) returns
only `ranking_id` and the authenticated caller's current local `capabilities`.
Active membership with read access is required, including for public rankings;
this endpoint is not the anonymous public ranking view. Active owners receive
all local capabilities. Global administrator roles do not bypass membership.
Missing and inaccessible rankings both return `404` with
`{"code":"ranking.not_found"}`. Success and scope-denial responses use `no-store`.
The server reads membership and grants together for each request, rather than
trusting token role claims, request headers or previously cached grants.

`GET /rankings/access` lists only the caller's currently accessible rankings,
with the same minimal item projection. It accepts an exclusive UUID `after`
cursor and `limit` from 1 to 100 (default 20). The response contains `items`
and `next_cursor` (explicitly null at the end), never a global total.
Pagination filters access before applying the limit, uses UUID ascending order,
and rechecks current access on every request. Invalid page parameters return
`400` with `{"code":"ranking.invalid_page"}`; responses are not cacheable.
These limits bound requests; they are not ranking creation quotas.

V2 adds ranking authorization tables without copying or modifying the historical
movie collection. Owner membership is checked at transaction commit, and scoped
grants require the corresponding membership. Existing-user references are
restrictive, not cascading history deletions. This slice exposes no ranking
creation, ownership transfer or archive API.


`DELETE /rankings/{rankingId}/membership/self` deactivates only the caller's
existing non-owner membership. It does not require READ and returns idempotent
`204` for an already inactive membership. Missing ranking/membership returns
generic `404 ranking.not_found`; the current owner receives
`409 ranking.owner_exit_requires_destination` without any change.
The transaction locks ranking then membership and preserves grants and historical
ratings. Departure starts no recovery TTL and publishes no external event.
Current access endpoints observe committed departure with the same token.
This does not implement account deletion, owner exit or realtime session cutover.


`PUT /rankings/{rankingId}/members/{memberId}/capabilities` replaces an active
non-owner member's grants as one transaction. Only the current active owner may
perform it; MANAGE_MEMBERS and instance administrator roles are not delegated
authority for this endpoint. Body: `{"capabilities":["READ","RATE_OWN"]}`.
An empty set revokes all grants. Known duplicates normalize to a set; missing,
null, non-string, unknown or more than 100 input values return
`400 ranking.invalid_capabilities` without mutation.
Successful replacement returns `204`. Unauthorized/missing/inactive contexts
return generic `404 ranking.not_found`; editing the owner's own grants returns
`409 ranking.owner_capabilities_immutable`. Responses use no-store.
Ranking/member locks revalidate ownership and active state. Insert failure rolls
back the entire replacement. Global roles, membership status, historical ratings
and XP are unchanged; no outbound event or newly delegated authority is created.

Ranking persistence/transaction failures return only `500 ranking.operation_failed`
with no-store, without exposing SQL or exception details. This error boundary is
scoped to ranking controllers and does not change unrelated legacy APIs.

V3 adds nullable internal `ranking.archived_at` without rewriting existing data.
Once set, its timestamp cannot be cleared or changed. Archived access projections
return only READ to callers who already qualify; grant changes are rejected with
`409 ranking.archived_read_only`. This is an internal foundation: there is no
standalone archive endpoint, account-deletion workflow or reopen operation.
Voluntary self-departure retains its existing behavior; complete lifecycle
activation and legacy data cutover are not provided by this increment.

Administrative account deletion (both supported DELETE and POST routes) returns
`409 account.deletion_requires_history_migration` with no-store when the account
has legacy ratings or any ranking/membership link, including inactive membership.
The user row is locked before reference checks; protected accounts, credentials,
ratings and wishlists remain unchanged. Accounts without these references retain
the legacy deletion behavior. This guard does not implement recoverable account
deletion, pseudonymization, or legacy ranking assignment.

V4 adds an empty contextual author-identity foundation. Allocation requires an
existing READ_COMMITTED transaction and contextual membership, participates in
outer rollback, and resolves bounded UUID collisions without sharing codes
between rankings. It does not assign legacy ratings or unlink accounts.
The detached-author projection contains only `kind: DELETED` and an opaque code;
it rejects a still-linked account. Clients localize the semantic label without
changing the code. No identity endpoint or account lifecycle is exposed.

### Legacy ranking rehearsal (not a rollout command)

V5 expands the separate `REMOVE_OWN_ENTRY` capability without broadening existing
`REMOVE_ENTRY` grants. V6 adds empty contextual movie entries, rounds and ratings;
V7 adds empty migration bookkeeping. Flyway never runs the backfill.

`OfflineLegacyRankingMigration` is an explicitly constructed, non-Spring offline
operator primitive, not an HTTP endpoint or automatic startup job. Its caller
must provide a reviewed DataSource/schema, an explicit owner UUID, a new ranking
UUID and a technical source-row bound (maximum 100,000). Preview returns a
fingerprinted plan; apply requires maintenance acknowledgement and rechecks the
plan after bounded database locks. Stop readers as well as writers: committing
memberships makes the ranking visible to existing access endpoints.

The approved initial cohort is every account present at the snapshot, in one
private invitation-only ranking. Members receive READ, RATE_OWN, ADD_ENTRY and
REMOVE_OWN_ENTRY; global ADM does not grant local powers. Only the chosen owner
has broad removal/invite authority initially. Later registrations are never
silently enrolled. A successful replay is read-only and requires unchanged
source and destination; changed data requires a new operational plan, not an
upsert over history. No communications, XP or application creation effects run.

The rehearsal preserves nullable/out-of-scale legacy ratings, nullable reveal
state, absent creators and original timestamps. It does not define how future
APIs interpret these exceptional states. Existing movie, wishlist and Discord
rows remain untouched. There is no consumer cutover, account deletion/recovery
flow, production authorization or guarantee of lossless rollback after new
contextual writes. Tests use only synthetic disposable PostgreSQL schemas.

### Contextual catalog browsing and existing-catalog inclusion

Authenticated readers can use `GET /rankings/{rankingId}/entries?after={entryId}&limit=20`
and `GET /rankings/{rankingId}/entries/{entryId}/rounds?afterNumber={number}&limit=20`.
Both filter authorization before keyset pagination in the same SQL snapshot,
return `items` plus explicit nullable `next_cursor`, and use `Cache-Control: no-store`.
Entry metadata has no ratings, authors, vote counts or aggregates. Round listing
preserves nullable `revealed` as raw historical state and does not reveal votes or
advance any timer. Invalid pages return400, inaccessible/missing scope404, and
persistence failures generic500. No public/anonymous route is enabled.

`POST /rankings/{rankingId}/entries/catalog` accepts only an authoritative
`movie_id` referring to an existing global catalog record. Current ADD_ENTRY or
ownership is revalidated under locks; global ADM is not an override. A new entry
gets a separate first round with hidden votes and its own creation time. Response
is `{entry_id}` with201 for creation or200 for an existing entry. Retry does not
open another round, change creator, reveal votes or refresh metadata. Archived
rankings reject the write. The transaction creates no global movie, notifications
or XP; rollback also removes any newly allocated contextual author identity.

This is **not full TMDB import**. Creating new catalog rows is blocked from this
flow because legacy list/detail/statistics still treat `movie` as a global
ranking. Full import and legacy writer compatibility need coordinated isolation.
Existing contextual FKs also protect catalog records against legacy deletion;
production activation must account for that rather than deleting history.
Missing provider metadata remains absent, not invented localized text. Future UI
fallbacks should be translated and may use original/English title candidates;
legacy required columns and consumers are unchanged until a compatible path is
verified. No fallback is persisted or new catalog insert enabled by these routes.

### Contextual own-rating writes

Authenticated clients can create (`POST`, 201) or explicitly edit (`PATCH`, 200)
`/rankings/{rankingId}/rounds/{roundId}/rating/me` with `{"score": 0}` through
`{"score": 10}`. The score must be a JSON integer: null, omission, decimal,
string, boolean and out-of-range values are rejected, not silently coerced.
The response contains only `round_id` and the authenticated author's `score`.
Creation of an existing rating returns 409 (`ranking.rating_exists`), without
turning retries into edits. Editing a missing rating returns the same generic
404 as an inaccessible context. Both responses and errors are `no-store`.

Membership and `RATE_OWN` (or ownership) are revalidated after the ranking lock.
A global administrator has no implicit participation privilege. Archived
rankings reject writes. A new member may rate an older round; rating a round
never changes its revelation state or starts a new window. Identical edits
preserve `updated_at`; actual edits preserve `created_at`. Concurrent edits are
serialized, with the last authorized write winning; this API does not claim
optimistic conflict detection. Historical null/out-of-range scores remain
untouched unless their own author explicitly edits that exact contextual rating.

These commands do not emit global notifications, websocket events or XP, and do
not call legacy movie-note usecases. They do not implement rating deletion or
restoration. Before activating a future soft-delete lifecycle, its state must be
checked by these commands as well as by contextual reads and catalog inclusion.

### Recoverable ranking-entry removal

Entries have a contextual, recoverable 24-hour lifecycle. `GET
/rankings/{rankingId}/entries/{entryId}/removal-preview` returns `entry_id`,
`version`, nullable `removed_at`, `expires_at`, `removal_token`, the localized-UI
warning key `ranking.entry_removal_permanent_after_retention`, and
`recovery_seconds: 86400`. It does not expose rating counts or aggregates.
`DELETE /rankings/{rankingId}/entries/{entryId}` requires
`{"expected_version": 0, "confirm": true}` and returns the new state. The
version comes from the preview, not a hardcoded client default. An outdated
version conflicts instead of extending the window or deleting a restored entry.
`POST /rankings/{rankingId}/entries/{entryId}/restore` requires the current
`removal_token`. A stale token conflicts, including after successful restoration.
All responses are no-store. The frontend must show the recovery deadline and
permanent-loss warning before confirmation, in the selected interface language.

Active owners or members with `REMOVE_ENTRY` can remove/restore any entry;
`REMOVE_OWN_ENTRY` permits only their own persisted inclusions. READ is not an
additional requirement. Re-including the same catalog movie with `ADD_ENTRY`
before the deadline restores its existing history, not a duplicate entry/round.
Archived rankings reject user mutations. Removed entries are absent from active
entry/round reads and cannot receive ratings. At or after the database deadline,
restore/re-inclusion is rejected even if physical cleanup has not yet run.

Expiration is an explicit internal batch operation, not an HTTP endpoint,
startup action or enabled scheduler. It removes only expired contextual
ratings/rounds/entries, never the global movie or other rankings. Legacy mapping
receipts retain original IDs and a terminal purge marker; their live references
are closed transactionally, and offline migration replay is blocked thereafter.
Previously requested removals continue to expire even if their ranking is later archived; active archived history is not purged. Operational scheduling remains disabled pending cutover. Deleting accounts,
individual ratings or ex-member ratings is not covered by this entry lifecycle.

`GET /rankings/{rankingId}/rounds/{roundId}/rating/me` reads only the current
member's own rating, requiring active READ membership or ownership. It returns
`round_id` and `score`, including explicit null or historical out-of-range values
without normalization. Hidden rounds and archived rankings remain readable to
the authorized author; removed entries, missing own ratings and inaccessible
contexts share the same generic 404. This read neither reveals others' votes nor
updates timestamps, creates author identities or changes revelation.

`GET /rankings/{rankingId}/entries/{entryId}/ratings/aggregate` is an authenticated,
read-only projection for active `READ` members or the active owner. It returns only
`entry_id`, exact integer `total`, integer `votes`, and `partial`. The total and
count include numeric scores from rounds whose persisted `revealed` flag is
explicitly true. Historical null scores are not numeric votes and do not become
zero; historical out-of-range scores remain unchanged and contribute their exact
value. The ratio `total / votes` is the weighted overall mean, not a mean of round
means. With zero votes, no mean exists; the response does not invent a zero mean
or select display precision. Clients must preserve integer precision.

`partial` means at least one round is not explicitly revealed, even if it has no
votes. No hidden vote values, participation counts, authors, or timestamps appear
in this projection. Changing hidden votes does not change its response. This read
never advances revelation or changes its timer. Archived history remains readable
under current authorization; removed entries, missing entries, and inaccessible
rankings return the same generic 404. Authorization and aggregation share one SQL
snapshot and responses use `Cache-Control: no-store`. Public/anonymous projections,
ranking-wide ordering, and event delivery are not introduced by this endpoint.

### Account-wide progression foundation

XP progression belongs to the account within this installation, not to a ranking
membership. Authorized awards from different rankings feed the same account's
balance. `OwnAccountProgressProjection` projects a trusted local balance only for
its expected account and requires an explicitly supplied `ProgressionPolicy`.
Its `LevelProgress` output has no ranking IDs, source events, award counts or
origin history; equal balances under the same policy yield equal progress.

This pure component is not an HTTP authorization boundary, storage reader or XP
awarder. It selects no production starting level, unit, curve, reward, cap,
retention or correction policy. There is no new endpoint, persistence or
historical backfill. Integration remains gated on those policies and current
account authorization. Progression never grants ranking permissions or changes
movie rating averages. No public visibility or cross-installation account link
is introduced by this foundation.

### Ranking directory and creation

Authenticated `GET /rankings?after=<ranking UUID>&limit=20` lists only rankings
where the actor is the active owner or a current active member with `READ`.
It returns `items` with `ranking_id`, `name`, `visibility`, `admission` and
`capabilities`, plus nullable `next_cursor`. Unnamed historical rankings return
an explicit null name; archived rankings are read-only. This no-store endpoint
does not replace `GET /rankings/access` or expose a public directory.

`POST /rankings` accepts only `name`, `visibility` (`PUBLIC` or `PRIVATE`) and
`admission` (`OPEN`, `APPROVAL` or `INVITATION`). It creates a ranking and
the authenticated owner's active membership atomically, returns the same summary
with HTTP 201, and does not perform invitations, public reads, XP or legacy edits.
Admission settings are stored independently of visibility; joining is not
introduced by this slice.

Creation fails closed (`503 ranking.creation_unavailable`) without both
`ranking.creation.max-owned` (positive integer) and
`ranking.creation.include-archived` (explicit boolean). There are no production
defaults. Quotas apply per owner within the installation, returning
`409 ranking.creation_limit_reached` if exhausted. Commands that introduce a new
owner or change quota eligibility in future must coordinate with this quota
invariant before activation. Offline legacy backfill is a separate, explicitly
maintained operation and creates no artificial historical names.

### Account-bound ranking invitations

A signed-in account can fetch its own nullable recipient code with
`GET /user/ranking-invitation-address`, and generate or replace it with
`POST /user/ranking-invitation-address/rotate`. A code is not a credential.
Share it only with someone who should be able to invite this account; there is no
account directory or general resolver.

`POST /rankings/{rankingId}/invitations` takes only `recipient_code`. A current
owner or member with `INVITE_MEMBER` may issue one live, single-use invitation
per eligible account in an active invitation-only ranking. It expires after
168 hours. Rotating a recipient code affects future issuance, not invitations
already linked to the account. The recipient must explicitly accept via
`POST /rankings/{rankingId}/invitations/{inviteId}/accept`; acceptance atomically
creates membership and `READ` / `RATE_OWN` grants. A current owner or the
still-authorized issuing member can revoke a pending invitation with
`DELETE /rankings/{rankingId}/invitations/{inviteId}`.

These are private no-store contracts. The invitation ID grants no access by
itself, and acceptance does not reactivate an inactive membership. No email,
Discord, or other external notification is sent. Rate limits and rollout verification are separate release requirements.

Provision different Base64-encoded 32-byte secrets for
`ranking.invitation.address.digest-key` and
`ranking.invitation.address.encryption-key` before enabling the flow. The database
stores an indexed HMAC digest and an account-bound encrypted envelope, never the
clear code. Unconfigured address reads/rotation and new issuance fail closed
with `503 ranking.invitation_address_unavailable`; acceptance/revocation of
already-issued invitations does not depend on these keys. Keep both secrets
stable across replicas/restarts. Changing key material after issuance requires a
separately planned operational rotation.

Signed-in recipients can list their own usable invitations with
`GET /ranking-invitations/incoming?after=<invite UUID>&limit=20`. Each item
contains only `invite_id`, `ranking_id`, nullable `ranking_name` and
`expires_at`, plus `next_cursor` at page level. The current issuer's
authorization and both accounts' eligibility are checked before pagination.
Once expired, revoked, consumed or unusable, the invitation stops contributing
a name or an item. Seeing an invitation never authorizes reading the ranking
before acceptance. Missing historical names remain null.

### Canonical movie catalog versus legacy board

The shared `movie` table retains canonical movie identity. V12 adds immutable
`legacy_eligible`: existing rows and old writers remain eligible by default;
an explicitly ineligible row cannot enter the old movie board, notes, genre or
stats projections, wishlist rated lookup or Discord history. Contextual ranking
entries may still reference it through authorized ranked reads. Identity
collisions do not auto-promote or overwrite a context-only movie. Offline
backfill uses only eligible rows and preserves the original fingerprint format.

This boundary does not activate contextual provider import. Upgrade all legacy
readers/replicas before introducing any context-only catalog row; an older
binary does not have this filter. Do not roll back to an unfiltered binary
while such rows remain present. Direct production catalog writes, backfill and
cutover require separate operational verification.

`POST /rankings/{rankingId}/entries/tmdb` is a private provider import, not a
legacy writer. The body is only `tmdb_id` (positive JSON integer up to
9,007,199,254,740,991) and success returns only `entry_id` (201 new entry; 200
existing or restored). Canonical matches are reused without refreshing metadata.
If absent, KIT is resolved outside write locks; ranking authority and deduplication
are rechecked in the write transaction. New movies are contextual-only, and
missing metadata may remain null. Existing legacy metadata requirements remain
checked by the database.

This operation is **disabled by default**, including the reuse path. Set
`ranking.catalog.tmdb-import-enabled=true` only after all readers, writers,
replicas and jobs are verified V12+ compatible and any old queues are drained.
Turning the toggle off does not change already imported catalog data or make
an older unfiltered binary safe. Neither import nor reuse emits legacy alerts,
affects other rankings' scores, or awards XP.

### Default round disclosure on detail

Authenticated `GET /rankings/{rankingId}/entries/{entryId}/rounds/{roundId}`
returns only `round_id`, `round_number` and nullable `revealed`. This specific
detail action can reveal a currently readable, active round whose flag is
explicitly false once its contextual creation time is at least 30 minutes old.
Authorization and elapsed time are rechecked under database locks. List and
aggregate reads, malformed or forbidden requests, archived rankings and unknown
legacy flags never change disclosure. HEAD does not trigger the transition.
Use this detail only when requested; do not prefetch it in SSR, list scans or PWA
background paths.

The response contains no vote, author, timing or statistics data and is no-store.
Only the established revealed-only aggregate may be refreshed afterward; hidden
rounds never contribute. The transition does not edit scores or contextual
timestamps, notify anyone, or reopen after a rating edit.
