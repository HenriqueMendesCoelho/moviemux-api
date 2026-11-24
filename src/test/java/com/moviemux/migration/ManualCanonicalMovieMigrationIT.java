package com.moviemux.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moviemux.ranking.adapter.repository.postgres.OfflineLegacyRankingMigration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ManualCanonicalMovieMigrationIT {
  private static final String FINGERPRINT = "a".repeat(64);
  private String url, user, password, schema, runtimeRole;
  private DriverManagerDataSource source;
  private JdbcTemplate jdbc;

  @BeforeEach void disposableDatabase() throws SQLException {
    url = System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    String suffix = UUID.randomUUID().toString().replace("-", "");
    try (var c = DriverManager.getConnection(url, user, password); var s = c.createStatement()) {
      s.execute("create schema movie_manual_test_" + suffix);
      schema = "movie_manual_test_" + suffix;
      s.execute("create role movie_manual_runtime_" + suffix + " login");
      runtimeRole = "movie_manual_runtime_" + suffix;
      s.execute("grant usage on schema " + schema + " to " + runtimeRole);
    }
    source = new DriverManagerDataSource(url, user, password) {
      @Override public Connection getConnection() throws SQLException {
        var c = super.getConnection();
        c.setSchema(schema);
        return c;
      }
    };
    jdbc = new JdbcTemplate(source);
  }

  @AfterEach void disposeOnlyOwnedResources() throws SQLException {
    if (schema != null) try (var c = DriverManager.getConnection(url, user, password); var s = c.createStatement()) {
      s.execute("drop schema " + schema + " cascade");
      if (runtimeRole != null) s.execute("drop role " + runtimeRole);
    }
  }

  @Test void v18PreservesV17FencedPlanAndActiveReplayWithoutAddingManualFilmToLegacySource() {
    assertThat(flyway("17").migrate().migrationsExecuted).isEqualTo(17);
    UUID owner = UUID.randomUUID(), rank = UUID.randomUUID(), movie = UUID.randomUUID();
    jdbc.update("insert into \"user\"(id,created_at,email,name,password) values (?,now(),?,'Synthetic','synthetic')",
        owner, owner + "@example.invalid");
    jdbc.update("""
        insert into movie(id,created_at,description,director,english_title,portuguese_title,user_id,show_notes)
        values (?,now(),'Synthetic','Synthetic','Synthetic','Synthetic',?,false)
        """, movie, owner);
    assertThat(jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),11)", movie, owner))
        .isEqualTo(1);
    var operator = new OfflineLegacyRankingMigration(source, 100, Set.of(runtimeRole));
    operator.fence(owner, rank, true);
    var plan = operator.preview(owner, rank);
    assertThat(plan).hasFieldOrPropertyWithValue("movies", 1L).hasFieldOrPropertyWithValue("ratings", 1L);
    var oldSource = legacyRows("to_jsonb(m)");
    var oldHistory = historyThrough17();
    var oldCutover = rows("legacy_board_cutover");
    var oldConstraints = movieConstraints();

    var v18 = flyway("18");
    assertThat(v18.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(v18.migrate().migrationsExecuted).isZero();
    assertThat(historyThrough17()).isEqualTo(oldHistory);
    assertThat(rows("legacy_board_cutover")).isEqualTo(oldCutover);
    assertThat(movieConstraints()).containsAll(oldConstraints).hasSize(oldConstraints.size() + 2);
    assertThat(legacyRows("to_jsonb(m)-'manual_command_id'-'manual_command_version'-'manual_command_fingerprint'"))
        .isEqualTo(oldSource);
    assertThat(jdbc.queryForMap("""
        select manual_command_id,manual_command_version,manual_command_fingerprint from movie where id=?
        """, movie)).containsEntry("manual_command_id", null).containsEntry("manual_command_version", null)
        .containsEntry("manual_command_fingerprint", null);

    UUID manualDuringFence = UUID.randomUUID();
    assertThat(insertMovie(manualDuringFence, false, UUID.randomUUID(), 1, FINGERPRINT)).isEqualTo(1);
    assertThat(operator.preview(owner, rank)).isEqualTo(plan);
    assertThat(operator.apply(plan, true)).isEqualTo(new OfflineLegacyRankingMigration.Result(false, 1, 1, 1));
    assertThat(jdbc.queryForObject("select current_legacy_board_phase()", String.class)).isEqualTo("ACTIVE");
    assertThat(jdbc.queryForList("select movie_id from ranking_movie_entry where ranking_id=?", UUID.class, rank))
        .containsExactly(movie).doesNotContain(manualDuringFence);
    assertThat(jdbc.queryForList("select score from ranking_movie_rating where ranking_id=?", Integer.class, rank))
        .containsExactly(11);
    var ledger = rows("legacy_ranking_migration");
    var mappings = rows("legacy_ranking_movie_map");
    var active = rows("legacy_board_cutover");

    assertThat(insertMovie(UUID.randomUUID(), false, UUID.randomUUID(), 1, FINGERPRINT)).isEqualTo(1);
    assertThat(operator.preview(owner, rank)).isEqualTo(plan);
    assertThat(operator.apply(plan, true)).isEqualTo(new OfflineLegacyRankingMigration.Result(true, 1, 1, 1));
    assertThat(rows("legacy_ranking_migration")).isEqualTo(ledger);
    assertThat(rows("legacy_ranking_movie_map")).isEqualTo(mappings);
    assertThat(rows("legacy_board_cutover")).isEqualTo(active);
    assertThat(legacyRows("to_jsonb(m)-'manual_command_id'-'manual_command_version'-'manual_command_fingerprint'"))
        .isEqualTo(oldSource);
  }

  @Test void commandMarkerIsUniqueCanonicalOnlyVersionedAndImmutableEvenOnOldMovies() {
    flyway("17").migrate();
    UUID old = UUID.randomUUID(), command = UUID.randomUUID();
    jdbc.update("insert into movie(id,created_at,english_title,legacy_eligible) values (?,now(),'Existing canonical',false)", old);
    flyway("18").migrate();
    String check = "movie_manual_command_metadata_ck";
    String immutable = "movie_manual_command_immutable";
    for (int subset = 1; subset < 7; subset++) {
      UUID id = (subset & 1) != 0 ? command : null;
      Integer version = (subset & 2) != 0 ? 1 : null;
      String fingerprint = (subset & 4) != 0 ? FINGERPRINT : null;
      expectConstraint("23514", check, () -> insertMovie(UUID.randomUUID(), false, id, version, fingerprint));
    }
    expectConstraint("23514", check, () -> insertMovie(UUID.randomUUID(), true, command, 1, FINGERPRINT));
    expectConstraint("23514", check, () -> insertMovie(UUID.randomUUID(), false, command, 2, FINGERPRINT));
    for (String hash : List.of("A".repeat(64), "x".repeat(64), "a".repeat(63))) {
      expectConstraint("23514", check, () -> insertMovie(UUID.randomUUID(), false, command, 1, hash));
    }

    UUID manual = UUID.randomUUID();
    assertThat(insertMovie(manual, false, command, 1, FINGERPRINT)).isEqualTo(1);
    assertThat(insertMovie(UUID.randomUUID(), false, null, null, null)).isEqualTo(1);
    expectConstraint("23505", "movie_manual_command_id_uk",
        () -> insertMovie(UUID.randomUUID(), false, command, 1, FINGERPRINT));
    expectConstraint("23514", immutable, () -> jdbc.update("""
        update movie set manual_command_id=?,manual_command_version=1,manual_command_fingerprint=? where id=?
        """, UUID.randomUUID(), FINGERPRINT, old));
    expectConstraint("23514", immutable, () -> jdbc.update("""
        update movie set manual_command_id=null,manual_command_version=null,manual_command_fingerprint=null where id=?
        """, manual));
    expectConstraint("23514", immutable,
        () -> jdbc.update("update movie set manual_command_id=? where id=?", UUID.randomUUID(), manual));
    expectConstraint("23514", immutable,
        () -> jdbc.update("update movie set manual_command_version=2 where id=?", manual));
    expectConstraint("23514", immutable,
        () -> jdbc.update("update movie set manual_command_fingerprint=? where id=?", "b".repeat(64), manual));
    assertThat(jdbc.update("update movie set english_title='Reviewed title' where id=?", manual)).isEqualTo(1);
    assertThat(jdbc.queryForMap("select manual_command_id,manual_command_fingerprint from movie where id=?", manual))
        .containsEntry("manual_command_id", command).containsEntry("manual_command_fingerprint", FINGERPRINT);
    assertThat(jdbc.queryForObject("select count(*) from movie where manual_command_id is not null", Long.class))
        .isEqualTo(1L);
  }

  private int insertMovie(UUID movie, boolean eligible, UUID command, Integer version, String fingerprint) {
    return jdbc.update("""
        insert into movie(id,created_at,description,director,english_title,portuguese_title,legacy_eligible,
           manual_command_id,manual_command_version,manual_command_fingerprint)
        values (?,now(),'Synthetic','Synthetic','Synthetic','Synthetic',?,?,cast(? as smallint),?)
        """, movie, eligible, command, version, fingerprint);
  }

  private List<String> legacyRows(String movieProjection) {
    return jdbc.queryForList("select (" + movieProjection + ")::text from movie m where legacy_eligible"
        + " union all select to_jsonb(n)::text from movie_note n order by 1", String.class);
  }

  private List<String> historyThrough17() {
    return jdbc.queryForList("""
        select version||':'||checksum from flyway_schema_history where installed_rank<=17 order by installed_rank
        """, String.class);
  }

  private List<String> movieConstraints() {
    return jdbc.queryForList("""
        select conname||':'||pg_get_constraintdef(oid) from pg_constraint
        where conrelid='movie'::regclass or confrelid='movie'::regclass order by 1
        """, String.class);
  }

  private List<String> rows(String table) {
    return jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class);
  }

  private Flyway flyway(String target) {
    return Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
        .createSchemas(false).cleanDisabled(true).target(target).load();
  }

  private void expectConstraint(String state, String name, Runnable action) {
    assertThatThrownBy(action::run).isInstanceOfSatisfying(DataAccessException.class,
        error -> assertThat(error.getMostSpecificCause()).isInstanceOfSatisfying(PSQLException.class, sql -> {
          assertThat(sql.getSQLState()).isEqualTo(state);
          assertThat(sql.getServerErrorMessage().getConstraint()).isEqualTo(name);
        }));
  }
}
