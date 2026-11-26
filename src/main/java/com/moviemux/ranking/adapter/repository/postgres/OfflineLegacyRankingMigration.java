package com.moviemux.ranking.adapter.repository.postgres;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit offline operator primitive. Not a bean, CLI, endpoint, startup task, or production approval.
 * The caller must supply an isolated/approved DataSource with an explicit schema and stop all writers.
 * Applying publishes memberships to existing access readers, so maintenance includes stopping readers.
 * No source rows are updated, no effects emitted, and no scoring/null interpretation is invented.
 */
public final class OfflineLegacyRankingMigration {
  private static final String KEY = "legacy-movies-v1";
  private static final List<String> TARGET_TABLES = List.of("ranking", "ranking_membership",
      "ranking_membership_grant", "ranking_author_identity", "ranking_movie_entry",
      "ranking_movie_round", "ranking_movie_rating", "legacy_ranking_movie_map");
  private final JdbcTemplate jdbc;
  private final DataSourceTransactionManager manager;
  private final RankingAuthorIdentityPostgresAdapter authors;

  private final int maxSourceRows;
  private final Set<String> runtimeRoles;

  public OfflineLegacyRankingMigration(DataSource source, int maxSourceRows) {
    this(source, maxSourceRows, Set.of());
  }

  /** All still-connected prospective runtime login roles must be explicitly inventoried for V17. */
  public OfflineLegacyRankingMigration(DataSource source, int maxSourceRows, Set<String> runtimeRoles) {
    if (maxSourceRows < 1 || maxSourceRows > 100_000) throw new IllegalArgumentException("migration.invalid_bound");
    this.jdbc = new JdbcTemplate(Objects.requireNonNull(source));
    this.manager = new DataSourceTransactionManager(source);
    this.authors = new RankingAuthorIdentityPostgresAdapter(new NamedParameterJdbcTemplate(source));
    this.maxSourceRows = maxSourceRows;
    this.runtimeRoles = Set.copyOf(Objects.requireNonNull(runtimeRoles));
    if (this.runtimeRoles.size() > 32 || this.runtimeRoles.stream().anyMatch(role -> role.isBlank()
        || role.getBytes(StandardCharsets.UTF_8).length > 63 || role.codePoints().anyMatch(Character::isISOControl)))
      throw new IllegalArgumentException("migration.invalid_runtime_roles");
  }

  public record Plan(UUID ownerId, UUID rankingId, String fingerprint, long users, long movies, long ratings) {
    public Plan {
      Objects.requireNonNull(ownerId); Objects.requireNonNull(rankingId); Objects.requireNonNull(fingerprint);
      if (!fingerprint.matches("[0-9a-f]{64}") || users < 1 || movies < 0 || ratings < 0)
        throw new IllegalArgumentException("migration.invalid_plan");
    }
  }
  public record Result(boolean alreadyApplied, long users, long movies, long ratings) {}

  /**
   * Enter a durable maintenance fence, never a startup action. Acknowledgement attests that old
   * pods, direct/transient writers, and queued effects were independently drained and the backup verified.
   */
  public void fence(UUID ownerId, UUID rankingId, boolean maintenanceAcknowledged) {
    Objects.requireNonNull(ownerId); Objects.requireNonNull(rankingId);
    requireMaintenance(maintenanceAcknowledged);
    transaction(false).executeWithoutResult(status -> {
      setupSession();
      if (!legacySource().interlocked()) throw new IllegalStateException("migration.interlock_schema_required");
      var gate = phase(true);
      var proof = runtimeRoleDigest();
      if (gate.mode() == Mode.FENCED) {
        requireBinding(gate, ownerId, rankingId);
        requireRoleDigest(gate, proof);
        return;
      }
      if (gate.mode() != Mode.LEGACY || count("select count(*) from legacy_ranking_migration where migration_key=?",KEY)!=0)
        throw new IllegalStateException("migration.already_applied_different_state");
      requireOwner(ownerId);
      if (count("select count(*) from ranking where id=?",rankingId)!=0)
        throw new IllegalStateException("migration.destination_not_empty");
      if (jdbc.update("""
          update legacy_board_cutover set phase='FENCED',owner_user_id=?,ranking_id=?,
            runtime_roles_digest=?,fenced_at=clock_timestamp() where singleton and phase='LEGACY'
          """,ownerId,rankingId,proof)!=1)
        throw new IllegalStateException("migration.interlock_unavailable");
    });
  }

  public Plan preview(UUID ownerId, UUID rankingId) {
    Objects.requireNonNull(ownerId); Objects.requireNonNull(rankingId);
    return transaction(true).execute(status -> {
      setupSession(); var source = legacySource();
      if (source.interlocked()) requireBinding(phase(false), ownerId, rankingId);
      requireReplayOpen(source);
      return snapshot(ownerId, rankingId, source);
    });
  }

  public Result apply(Plan plan, boolean maintenanceAcknowledged) {
    Objects.requireNonNull(plan);
    requireMaintenance(maintenanceAcknowledged);
    return transaction(false).execute(status -> {
      setupSession(); var source = legacySource();
      Phase gate = null;
      if (source.interlocked()) {
        gate = phase(true);
        requireBinding(gate, plan.ownerId(), plan.rankingId());
        // Fresh privilege proof before any first apply. ACTIVE replay does not open or change phase.
        if (gate.mode() == Mode.FENCED) requireRoleDigest(gate, runtimeRoleDigest());
      }
      // Serialize applies before locking sources. SHARE blocks INSERT/UPDATE/DELETE while allowing reads.
      jdbc.execute("lock table legacy_ranking_migration in share row exclusive mode");
      jdbc.execute("lock table \"user\", movie, movie_note in share mode");
      jdbc.execute("lock table ranking, ranking_membership, ranking_membership_grant, ranking_author_identity, ranking_movie_entry, ranking_movie_round, ranking_movie_rating, legacy_ranking_movie_map in share mode");
      requireReplayOpen(source);
      Plan current = snapshot(plan.ownerId(), plan.rankingId(), source);
      if (!plan.equals(current)) throw new IllegalStateException("migration.source_changed");
      var ledger = jdbc.queryForList("select * from legacy_ranking_migration where migration_key=?", KEY);
      if (!ledger.isEmpty()) {
        if (gate != null && gate.mode() != Mode.ACTIVE)
          throw new IllegalStateException("migration.already_applied_different_state");
        var previous = ledger.get(0);
        if (((Number) previous.get("algorithm_version")).intValue() != 1
            || ((Number) previous.get("source_users")).longValue() != plan.users()
            || ((Number) previous.get("source_movies")).longValue() != plan.movies()
            || ((Number) previous.get("source_ratings")).longValue() != plan.ratings()
            || !plan.ownerId().equals(previous.get("owner_user_id"))
            || !plan.rankingId().equals(previous.get("ranking_id"))
            || !plan.fingerprint().equals(previous.get("source_fingerprint"))
            || !targetFingerprint(plan.rankingId()).equals(previous.get("target_fingerprint")))
          throw new IllegalStateException("migration.already_applied_different_state");
        reconcile(plan, source);
        return new Result(true, plan.users(), plan.movies(), plan.ratings());
      }
      if (count("select count(*) from ranking where id=?", plan.rankingId()) != 0)
        throw new IllegalStateException("migration.destination_not_empty");
      if (gate != null) {
        if (gate.mode()!=Mode.FENCED || jdbc.update("""
            update legacy_board_cutover set phase='ACTIVE', activated_at=greatest(clock_timestamp(),fenced_at)
            where singleton and phase='FENCED'
            """)!=1) throw new IllegalStateException("migration.interlock_unavailable");
        // No other transaction sees ACTIVE before reconciliation and the deferred ledger assertion succeed.
      }
      insertRankingAndMembers(plan);
      for (UUID account : jdbc.queryForList("select id from \"user\" order by id", UUID.class))
        authors.getOrCreate(plan.rankingId(), account);
      // Ledger and all destinations commit atomically. The placeholder digest is never externally visible.
      jdbc.update("""
          insert into legacy_ranking_migration(migration_key,ranking_id,owner_user_id,algorithm_version,
            source_fingerprint,target_fingerprint,source_users,source_movies,source_ratings,completed_at)
          values (?,?,?,1,?,?,?,?,?,current_timestamp)
          """, KEY, plan.rankingId(), plan.ownerId(), plan.fingerprint(), "0".repeat(64),
          plan.users(), plan.movies(), plan.ratings());
      for (UUID movie : jdbc.queryForList("select id from ("+source.movies()+") legacy order by id", UUID.class)) {
        UUID entry = UUID.randomUUID(), round = UUID.randomUUID();
        jdbc.update("""
            insert into ranking_movie_entry(ranking_id,entry_id,movie_id,added_by_author_id,created_at,updated_at)
            select ?,?,m.id,a.author_id,m.created_at,m.updated_at from movie m
            left join ranking_author_identity a on a.ranking_id=? and a.account_id=m.user_id where m.id=?
            """, plan.rankingId(), entry, plan.rankingId(), movie);
        jdbc.update("""
            insert into ranking_movie_round(ranking_id,entry_id,round_id,round_number,description,created_at,updated_at,revealed)
            select ?,?,?,1,null,created_at,updated_at,show_notes from movie where id=?
            """, plan.rankingId(), entry, round, movie);
        jdbc.update("""
            insert into legacy_ranking_movie_map(migration_key,ranking_id,legacy_movie_id,entry_id,round_id,live_entry_id,live_round_id)
            values (?,?,?,?,?,?,?)
            """, KEY, plan.rankingId(), movie, entry, round, entry, round);
      }
      jdbc.update("""
          insert into ranking_movie_rating(ranking_id,round_id,author_id,score,created_at,updated_at)
          select map.ranking_id,map.round_id,a.author_id,n.note,n.created_at,n.updated_at
          from movie_note n join legacy_ranking_movie_map map on map.legacy_movie_id=n.movie_id and map.migration_key=?
          join ranking_author_identity a on a.ranking_id=map.ranking_id and a.account_id=n.user_id
          """, KEY);
      reconcile(plan, source);
      jdbc.update("update legacy_ranking_migration set target_fingerprint=? where migration_key=?",
          targetFingerprint(plan.rankingId()), KEY);
      return new Result(false, plan.users(), plan.movies(), plan.ratings());
    });
  }

  private TransactionTemplate transaction(boolean preview) {
    if (TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("migration.requires_independent_transaction");
    var tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(preview ? TransactionDefinition.ISOLATION_REPEATABLE_READ : TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setReadOnly(preview); tx.setTimeout(60);
    return tx;
  }

  private void setupSession() {
    jdbc.execute("set local timezone='UTC'");
    jdbc.execute("set local lock_timeout='5s'");
    jdbc.execute("set local statement_timeout='60s'");
  }

  private LegacySource legacySource() {
    var versions = jdbc.queryForList("select version from flyway_schema_history where success order by installed_rank", String.class);
    int latest = versions.size();
    if (latest < 8 || latest > 19 || !versions.equals(java.util.stream.IntStream.rangeClosed(1, latest)
        .mapToObj(Integer::toString).toList()))
      throw new IllegalStateException("migration.schema_version_mismatch");
    if (count("select count(*) from flyway_schema_history where not success") != 0)
      throw new IllegalStateException("migration.schema_history_failed");
    boolean isolated = latest >= 12;
    // Preserve the original SQL column order and labels for the algorithm-1 fingerprint.
    // V19 adds instance registration state and waitlist only; neither changes the legacy
    // movie/user/rating source rows or the target reconciliation contract.
    String movies = "select id,created_at,description,director,english_title,english_url_trailer,imdb_id,"
        + "original_title,portuguese_title,portuguese_url_trailer,release_date,runtime,show_notes,tmdb_id,"
        + "updated_at,url_image,user_id from movie" + (isolated ? " where legacy_eligible" : "");
    String ratings = "select n.created_at,n.note,n.updated_at,n.movie_id,n.user_id from movie_note n"
        + (isolated ? " join movie m on m.id=n.movie_id and m.legacy_eligible" : "");
    return new LegacySource(movies, ratings, latest >= 14, latest >= 17);
  }

  private record LegacySource(String movies, String ratings, boolean repeatedRoundPolicy, boolean interlocked) {}
  private enum Mode { LEGACY, FENCED, ACTIVE }
  private record Phase(Mode mode, UUID owner, UUID ranking, String roleDigest) {}

  private Phase phase(boolean lock) {
    var rows = jdbc.query("select phase,owner_user_id,ranking_id,runtime_roles_digest from legacy_board_cutover where singleton"
        + (lock ? " for update" : ""), (row,index) -> new Phase(Mode.valueOf(row.getString("phase")),
            row.getObject("owner_user_id",UUID.class), row.getObject("ranking_id",UUID.class),
            row.getString("runtime_roles_digest")));
    if (rows.size()!=1) throw new IllegalStateException("migration.interlock_unavailable");
    return rows.getFirst();
  }

  private void requireBinding(Phase gate, UUID owner, UUID ranking) {
    if (gate.mode()==Mode.LEGACY) throw new IllegalStateException("migration.cutover_fence_required");
    if (!owner.equals(gate.owner()) || !ranking.equals(gate.ranking()))
      throw new IllegalStateException("migration.cutover_binding_mismatch");
  }

  private void requireRoleDigest(Phase gate, String digest) {
    if (!digest.equals(gate.roleDigest())) throw new IllegalStateException("migration.runtime_role_proof_changed");
  }

  private String runtimeRoleDigest() {
    if (runtimeRoles.isEmpty()) throw new IllegalStateException("migration.runtime_role_proof_required");
    var digest=digest();
    feed(digest,"legacy-board-runtime-roles-v1");
    int pgVersion=Objects.requireNonNull(jdbc.queryForObject(
        "select current_setting('server_version_num')::integer",Integer.class));
    for(String role:runtimeRoles.stream().sorted().toList()) {
      var oid=jdbc.queryForList("select oid::text from pg_roles where rolname=? and rolcanlogin",String.class,role);
      if (oid.size()!=1 || !Boolean.TRUE.equals(jdbc.queryForObject("""
          select has_function_privilege(cast(? as oid),'lock_legacy_board_phase()','EXECUTE')
            and not exists(
              select 1 from pg_roles accessible where pg_has_role(cast(? as oid),accessible.oid,'MEMBER') and (
                accessible.rolsuper or accessible.rolcreaterole or accessible.rolreplication
                or accessible.rolbypassrls or accessible.rolname in ('pg_write_all_data','pg_write_server_files','pg_execute_server_program')
                or has_schema_privilege(accessible.oid,current_schema(),'CREATE')
                or has_table_privilege(accessible.oid,'legacy_board_cutover','INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER')
                or has_any_column_privilege(accessible.oid,'legacy_board_cutover','INSERT,UPDATE')
                or exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace
                  where n.nspname=current_schema() and c.relowner=accessible.oid
                    and c.relname in ('legacy_board_cutover','ranking','movie','movie_note','movie_discord','user'))
              )
            )
          """,Boolean.class,oid.getFirst(),oid.getFirst()))) throw new IllegalStateException("migration.runtime_role_unsafe");
      // PostgreSQL 15 introduced grants of SET on individual parameters, including trigger bypass.
      if (pgVersion>=150000 && Boolean.TRUE.equals(jdbc.queryForObject("""
          select exists(select 1 from pg_roles accessible
            where pg_has_role(cast(? as oid),accessible.oid,'MEMBER')
              and has_parameter_privilege(accessible.oid,'session_replication_role','SET'))
          """, Boolean.class, oid.getFirst()))) throw new IllegalStateException("migration.runtime_role_unsafe");
      feed(digest,role);
      feed(digest,oid.getFirst());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void requireMaintenance(boolean acknowledged) {
    if (!acknowledged) throw new IllegalStateException("migration.maintenance_required");
  }

  private void requireReplayOpen(LegacySource source) {
    if (count("select count(*) from legacy_ranking_migration where migration_key=? and replay_closed_at is not null",KEY) != 0)
      throw new IllegalStateException("migration.replay_closed_after_context_purge");
    if (count("select count(*) from ranking_movie_entry where (removed_at is not null or lifecycle_version <> 0) and ranking_id in (select ranking_id from legacy_ranking_migration where migration_key=?)",KEY) != 0)
      throw new IllegalStateException("migration.already_applied_different_state");
    if (source.repeatedRoundPolicy() && count("""
        select count(*) from legacy_ranking_migration l join ranking k on k.id=l.ranking_id
        where l.migration_key=? and (k.repeat_rounds_enabled or exists (
          select 1 from ranking_movie_round r where r.ranking_id=k.id and r.round_number>1
        ))
        """, KEY) != 0)
      throw new IllegalStateException("migration.already_applied_different_state");
  }

  private Plan snapshot(UUID owner, UUID ranking, LegacySource source) {
    requireOwner(owner);
    long users=count("select count(*) from \"user\""),
        movies=count("select count(*) from ("+source.movies()+") legacy"),
        ratings=count("select count(*) from ("+source.ratings()+") legacy");
    if (ratings != count("select count(*) from movie_note"))
      throw new IllegalStateException("migration.ineligible_legacy_rating");
    if (users + movies + ratings > maxSourceRows) throw new IllegalStateException("migration.source_exceeds_bound");
    MessageDigest hash=digest(); feed(hash, KEY); feed(hash,owner.toString()); feed(hash,ranking.toString());
    hashQuery(hash, "users", "select row_to_json(s)::text from (select id,created_at from \"user\" order by id) s");
    hashQuery(hash, "movies", "select row_to_json(s)::text from ("+source.movies()+" order by id) s");
    hashQuery(hash, "ratings", "select row_to_json(s)::text from (select * from ("+source.ratings()+") legacy order by movie_id,user_id) s");
    return new Plan(owner,ranking,HexFormat.of().formatHex(hash.digest()),users,movies,ratings);
  }

  private void requireOwner(UUID owner) {
    if (count("select count(*) from \"user\" where id=?",owner)!=1)
      throw new IllegalArgumentException("migration.owner_not_found");
  }

  private void insertRankingAndMembers(Plan plan) {
    jdbc.update("insert into ranking(id,owner_user_id,visibility,admission,created_at) values (?,?,'PRIVATE','INVITATION',current_timestamp)",plan.rankingId(),plan.ownerId());
    jdbc.update("insert into ranking_membership(ranking_id,user_id,active,created_at) select ?,id,true,current_timestamp from \"user\"",plan.rankingId());
    jdbc.update("""
        insert into ranking_membership_grant(ranking_id,user_id,capability)
        select ?,u.id,g.capability from "user" u
        cross join (values ('READ'),('RATE_OWN'),('ADD_ENTRY'),('REMOVE_OWN_ENTRY')) g(capability)
        """,plan.rankingId());
  }

  private void reconcile(Plan plan, LegacySource source) {
    UUID rank=plan.rankingId();
    if (count("select count(*) from ranking_membership where ranking_id=?",rank)!=plan.users()
        || count("select count(*) from ranking_author_identity where ranking_id=?",rank)!=plan.users()
        || count("select count(*) from ranking_movie_entry where ranking_id=?",rank)!=plan.movies()
        || count("select count(*) from ranking_movie_round where ranking_id=?",rank)!=plan.movies()
        || count("select count(*) from ranking_movie_rating where ranking_id=?",rank)!=plan.ratings()
        || count("select count(*) from legacy_ranking_movie_map where ranking_id=?",rank)!=plan.movies())
      throw new IllegalStateException("migration.reconciliation_count_mismatch");
    // EXCEPT ALL compares nulls as equal and catches row/content differences, not only totals.
    String sourceMovies="select id,user_id,created_at,updated_at,show_notes from ("+source.movies()+") legacy";
    String targetMovies="""
        select e.movie_id,a.account_id,e.created_at,e.updated_at,r.revealed
        from legacy_ranking_movie_map m join ranking_movie_entry e on e.ranking_id=m.ranking_id and e.entry_id=m.entry_id
        join ranking_movie_round r on r.ranking_id=m.ranking_id and r.round_id=m.round_id and r.entry_id=e.entry_id
        left join ranking_author_identity a on a.ranking_id=e.ranking_id and a.author_id=e.added_by_author_id
        where m.ranking_id='%s'::uuid and r.round_number=1 and r.description is null
          and r.created_at=e.created_at and r.updated_at is not distinct from e.updated_at
        """.formatted(rank);
    String sourceRatings="select movie_id,user_id,note,created_at,updated_at from ("+source.ratings()+") legacy";
    String targetRatings="""
        select m.legacy_movie_id,a.account_id,r.score,r.created_at,r.updated_at from ranking_movie_rating r
        join legacy_ranking_movie_map m on m.ranking_id=r.ranking_id and m.round_id=r.round_id
        join ranking_author_identity a on a.ranking_id=r.ranking_id and a.author_id=r.author_id
        where r.ranking_id='%s'::uuid
        """.formatted(rank);
    if (!sameRows(sourceMovies,targetMovies)||!sameRows(sourceRatings,targetRatings))
      throw new IllegalStateException("migration.reconciliation_content_mismatch");
  }

  private boolean sameRows(String left,String right) {
    return count("select count(*) from (("+left+" except all "+right+") union all ("+right+" except all "+left+")) differences")==0;
  }
  private String targetFingerprint(UUID rank) {
    MessageDigest hash=digest();
    for(String table:TARGET_TABLES) {
      String column=table.equals("ranking")?"id":"ranking_id";
      // Algorithm1 preserves V7 JSON field order regardless of later schema expansion.
      String columns = switch(table) {
        case "ranking" -> "id,owner_user_id,visibility,admission,owner_membership_active,created_at,archived_at";
        case "ranking_membership" -> "ranking_id,user_id,active,created_at";
        case "ranking_movie_entry" -> "ranking_id,entry_id,movie_id,added_by_author_id,created_at,updated_at";
        case "ranking_movie_round" -> "ranking_id,entry_id,round_id,round_number,description,created_at,updated_at,revealed";
        case "legacy_ranking_movie_map" -> "migration_key,ranking_id,legacy_movie_id,entry_id,round_id";
        default -> "*";
      };
      hashQuery(hash,table,"select row_to_json(s)::text from (select "+columns+" from "+table
          +" where "+column+"='"+rank+"'::uuid) s order by row_to_json(s)::text");
    }
    return HexFormat.of().formatHex(hash.digest());
  }
  private long count(String sql,Object...params) { return Objects.requireNonNull(jdbc.queryForObject(sql,Long.class,params)); }
  private void hashQuery(MessageDigest hash,String label,String sql) {
    feed(hash,label); jdbc.query(sql,(RowCallbackHandler) row -> feed(hash,row.getString(1)));
  }
  private static void feed(MessageDigest hash,String value) {
    byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
    hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); hash.update(bytes);
  }
  private static MessageDigest digest() {
    try { return MessageDigest.getInstance("SHA-256"); }
    catch(NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
  }
}
