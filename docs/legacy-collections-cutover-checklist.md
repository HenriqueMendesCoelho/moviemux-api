# Legacy MovieMux collection: operator preparation

This is a preparation checklist, not approval to run a production cutover. The
repository currently exposes an offline migration primitive, not a supported
startup job, shell command, or public endpoint. Any actual invocation requires
a separately reviewed runner and target, including explicit authorization for
the environment and data. Use disposable synthetic PostgreSQL for development
and CI. Keep production snapshots in the owner's controlled infrastructure.

## Inputs to review before the maintenance window

| Input | Required evidence |
| --- | --- |
| Target | Confirm the expected environment, database, schema, PostgreSQL version and exact backend/gateway/frontend revisions out of band. Verify the runner is pointed only at that target, not the Coolify management database or another tenant. |
| Credentials | Use controlled configuration/secret storage for the approved target. Do not put a JDBC password, connection string with credentials, dump, email list, or account roster in Git, command history, a chat message or the verification report. |
| Owner | Confirm the intended existing MovieMux account inside the approved environment and supply its exact account UUID to the runner. Instance administrator status and display names are not substitutes. |
| Destination | Generate one unused collection UUID and carry that exact value through preview, review and apply. Do not overwrite an existing collection or switch owners across attempts. |
| Row bound | Set an explicit positive ceiling, at most 100,000 combined source users, eligible movies and ratings. If the snapshot exceeds the bound, design and rehearse a separate migration; do not bypass it. |
| Restore point | Confirm an approved snapshot/PITR position and evidence it restores into an isolated target. An untested backup is not a rollback plan. |
| Flyway | Validate the exact candidate with the approved startup baseline/history. Rehearse both pristine and already-baselined databases. Check fallback against the same post-migration Flyway history; do not delete history, downgrade schemas or skip validation to boot an old binary. |

The selected owner and destination identifiers belong in a controlled runbook.
The general handoff can report the exact build, test/run IDs, pass/fail,
counts, and whether the reviewed fingerprints match, without source rows or
sensitive identifiers.

## Evidence to produce in rehearsal

1. Run the candidate in isolation without external mail, Discord/webhook, socket
   or provider effects. Do not let a rehearsal share a live database, DNS route,
   session cookie, secret, or worker. Capture only the relevant version,
   validation and result codes.
2. Preview with the approved owner, new collection UUID and row bound. The plan
   records those identifiers, the users/movies/ratings counts and an
   algorithm-versioned source fingerprint; it does not emit the data rows.
   Confirm that contextual-only catalog movies do not enter the legacy source.
3. Prove preservation with synthetic edge cases: no-note films, missing creators,
   hidden/unknown reveals, valid zero scores, null/out-of-range legacy scores,
   original timestamps and legacy catalog/wishlist data. Reconcile exact values
   and cardinalities, not merely record counts.
4. Verify the approved historical cohort becomes one private invite-only
   collection. The owner has scoped ownership; existing members can read, rate,
   add via TMDB and remove only their own entries. New registrations are not
   silently enrolled, and new invited members start with read/rate. No account
   address, raw account UUID, rating author linkage or private collection metadata
   should become visible through the new roster or any public projection.
5. Test the same reviewed plan after a failed/rolled-back apply and unchanged
   successful replay. Changed source/destination, wrong Flyway history, conflicts,
   unmet maintenance prerequisites, or a closed ledger stop the operation.
   Never use overwrite/recovery flags to reconcile a changed snapshot.

## Independent activation gates

- Stop relevant readers, writers and background jobs before any approved apply.
  Committing makes membership visible to existing readers; a database lock alone
  does not stop application reads. Generate a fresh preview under that maintenance
  fence, compare it to the reviewed inputs, and abort on drift.
- Bring every backend instance and gateway upstream onto a build serving both
  `/movies/collections` and `/rankings` before enabling the corresponding front.
  Exercise both through the gateway, not only directly on one backend. A fallback
  must serve both paths and tolerate the same exact schema while either client
  can still use it.
- Keep other gates independent: production collection quota must be explicitly
  set (archived collections count); enable TMDB/picker, own-rating copy and
  eligible entry cleanup only with their own verified config, protections and
  rehearsal. Do not mix XP, TV, new admission policy or new account rules into
  this snapshot.
- Before contextual writes, confirm the rollback decision and what the fallback
  UI can actually use. After new contextual writes, an old legacy reader cannot
  be assumed to see those changes; preserve them and use a reviewed forward-fix
  or explicit recovery plan instead of promising lossless rollback.
