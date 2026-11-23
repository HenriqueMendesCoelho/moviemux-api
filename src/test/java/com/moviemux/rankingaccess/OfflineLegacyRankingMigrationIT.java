package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.*;

import com.moviemux.ranking.adapter.repository.postgres.OfflineLegacyRankingMigration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class OfflineLegacyRankingMigrationIT {
  private static final List<String> SOURCES = List.of("\"user\"", "user_roles", "movie", "movie_note",
      "movie_discord", "movie_genre", "movies_genres", "user_wishlist", "movie_wishlist", "movie_wishlist_user_wishlist");
  private static final List<String> TARGETS = List.of("ranking", "ranking_membership", "ranking_membership_grant",
      "ranking_author_identity", "ranking_movie_entry", "ranking_movie_round", "ranking_movie_rating",
      "legacy_ranking_migration", "legacy_ranking_movie_map");
  private String url, user, password, schema;
  private JdbcTemplate jdbc;
  private DriverManagerDataSource source;
  private OfflineLegacyRankingMigration migration;
  private UUID owner, admin, voter, idle, rank;

  @BeforeEach void fixture() throws Exception {
    url = System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    schema = "offline_legacy_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
      statement.execute("create schema " + schema);
    }
    source = new DriverManagerDataSource(url, user, password) {
      @Override public Connection getConnection() throws SQLException {
        var connection = super.getConnection(); connection.setSchema(schema); return connection;
      }
    };
    jdbc = new JdbcTemplate(source);
    Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
        .createSchemas(false).cleanDisabled(true).target("16").load().migrate();
    migration = new OfflineLegacyRankingMigration(source, 10_000);
    owner = account(false); admin = account(true); voter = account(false); idle = account(false); rank = UUID.randomUUID();
    UUID first = movie(owner, true), second = movie(voter, false), third = movie(null, null);
    rating(first, owner, 8); rating(first, voter, null); rating(second, admin, -1); rating(third, voter, 11);
    jdbc.update("insert into movie_discord(movie_id,message_id) values (?, 'synthetic-message')", first);
    UUID wishlist = UUID.randomUUID();
    jdbc.update("insert into user_wishlist(id,created_at,name,shareable,user_id) values (?,now(),'Synthetic',false,?)", wishlist, idle);
    jdbc.update("insert into movie_wishlist(tmdb_id,url_image) values (987654,'synthetic')");
    jdbc.update("insert into movie_wishlist_user_wishlist(movie_id,wishlist_id,wishlist_order) values (987654,?,4)", wishlist);
  }

  @AfterEach void cleanup() throws Exception {
    if (schema != null) try (var connection = DriverManager.getConnection(url, user, password); var statement = connection.createStatement()) {
      statement.execute("drop schema " + schema + " cascade");
    }
  }

  @Test void copiesExactHistoryAndCohortWithoutChangingSourcesOrGrantingAdminPrivileges() {
    var before = snapshot(SOURCES);
    var plan = migration.preview(owner, rank);
    assertThat(plan.users()).isEqualTo(4); assertThat(plan.movies()).isEqualTo(3); assertThat(plan.ratings()).isEqualTo(4);
    assertEmptyTargets();
    var result = migration.apply(plan, true);
    assertThat(result.alreadyApplied()).isFalse();
    assertThat(result.users()).isEqualTo(4); assertThat(result.movies()).isEqualTo(3); assertThat(result.ratings()).isEqualTo(4);
    assertThat(snapshot(SOURCES)).isEqualTo(before);
    assertThat(jdbc.queryForMap("select owner_user_id,visibility,admission from ranking where id=?", rank))
        .containsEntry("owner_user_id", owner).containsEntry("visibility", "PRIVATE").containsEntry("admission", "INVITATION");
    assertThat(jdbc.queryForList("select user_id from ranking_membership where ranking_id=? and active", UUID.class, rank))
        .containsExactlyInAnyOrder(owner, admin, voter, idle);
    for (UUID account : List.of(owner, admin, voter, idle))
      assertThat(jdbc.queryForList("select capability from ranking_membership_grant where ranking_id=? and user_id=?", String.class, rank, account))
          .containsExactlyInAnyOrder("READ", "RATE_OWN", "ADD_ENTRY", "REMOVE_OWN_ENTRY");
    assertEquivalent("select id,user_id,created_at,updated_at,show_notes from movie", """
        select e.movie_id,a.account_id,e.created_at,e.updated_at,r.revealed from ranking_movie_entry e
        join ranking_movie_round r on r.ranking_id=e.ranking_id and r.entry_id=e.entry_id
        left join ranking_author_identity a on a.ranking_id=e.ranking_id and a.author_id=e.added_by_author_id
        """);
    assertEquivalent("select movie_id,user_id,note,created_at,updated_at from movie_note", """
        select e.movie_id,a.account_id,n.score,n.created_at,n.updated_at from ranking_movie_rating n
        join ranking_movie_round r on r.ranking_id=n.ranking_id and r.round_id=n.round_id
        join ranking_movie_entry e on e.ranking_id=r.ranking_id and e.entry_id=r.entry_id
        join ranking_author_identity a on a.ranking_id=n.ranking_id and a.author_id=n.author_id
        """);
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_round where round_number=1 and description is null", Long.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_entry where added_by_author_id is null", Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_round where revealed is null", Long.class)).isEqualTo(1);
    assertEquivalent("select created_at,updated_at from movie", "select created_at,updated_at from ranking_movie_round");
  }

  @Test void replayIsStableAndLaterRegistrationNeverJoinsTheLegacyCohort() {
    var plan = migration.preview(owner, rank); migration.apply(plan, true);
    var copied = snapshot(TARGETS);
    assertThat(migration.apply(plan, true).alreadyApplied()).isTrue();
    assertThat(snapshot(TARGETS)).isEqualTo(copied);
    UUID later = account(false);
    assertThatThrownBy(() -> migration.apply(plan, true)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> migration.apply(migration.preview(owner, rank), true)).isInstanceOf(IllegalStateException.class);
    assertThat(snapshot(TARGETS)).isEqualTo(copied);
    assertThat(jdbc.queryForObject("select count(*) from ranking_membership where user_id=?", Long.class, later)).isZero();
  }

  @Test void sameCountsButChangedSourceRejectsStaleFingerprint() {
    var plan = migration.preview(owner, rank);
    jdbc.update("update movie set description='changed synthetic description'");
    var before = snapshot(SOURCES);
    assertThatThrownBy(() -> migration.apply(plan, true)).isInstanceOf(IllegalStateException.class).hasMessage("migration.source_changed");
    assertEmptyTargets(); assertThat(snapshot(SOURCES)).isEqualTo(before);
  }

  @Test void missingOwnerMaintenanceAndBoundFailWithoutWrites() {
    var before = snapshot(SOURCES);
    assertThatThrownBy(() -> migration.preview(UUID.randomUUID(), rank)).isInstanceOf(IllegalArgumentException.class);
    var plan = migration.preview(owner, rank);
    assertThatThrownBy(() -> migration.apply(plan, false)).isInstanceOf(IllegalStateException.class).hasMessage("migration.maintenance_required");
    assertThatThrownBy(() -> new OfflineLegacyRankingMigration(source, 10).preview(owner, rank)).isInstanceOf(IllegalStateException.class);
    assertEmptyTargets(); assertThat(snapshot(SOURCES)).isEqualTo(before);
  }

  @Test void existingDestinationIsNeverAdoptedOrOverwritten() throws Exception {
    var plan = migration.preview(owner, rank);
    try (var connection = source.getConnection()) {
      connection.setAutoCommit(false);
      try (var insert = connection.prepareStatement("insert into ranking(id,owner_user_id,visibility,admission,created_at) values (?,?,'PRIVATE','INVITATION',now())");
           var member = connection.prepareStatement("insert into ranking_membership(ranking_id,user_id,active,created_at) values (?,?,true,now())")) {
        insert.setObject(1, rank); insert.setObject(2, owner); insert.executeUpdate();
        member.setObject(1, rank); member.setObject(2, owner); member.executeUpdate(); connection.commit();
      }
    }
    var before = snapshot(TARGETS);
    assertThatThrownBy(() -> migration.apply(plan, true)).isInstanceOf(IllegalStateException.class).hasMessage("migration.destination_not_empty");
    assertThat(snapshot(TARGETS)).isEqualTo(before);
  }

  @Test void corruptedCompletedDestinationRejectsReplay() {
    var plan = migration.preview(owner, rank); migration.apply(plan, true);
    jdbc.update("delete from ranking_membership_grant where user_id=? and capability='ADD_ENTRY'", voter);
    var before = snapshot(TARGETS);
    assertThatThrownBy(() -> migration.apply(plan, true)).isInstanceOf(IllegalStateException.class).hasMessage("migration.already_applied_different_state");
    assertThat(snapshot(TARGETS)).isEqualTo(before);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"source_users", "source_movies", "source_ratings"})
  void immutableLedgerRejectsCountTamperingAndOriginalReplayStillSucceeds(String column) {
    var plan = migration.preview(owner, rank); migration.apply(plan, true);
    var sourceBefore = snapshot(SOURCES);
    var targetBefore = snapshot(TARGETS);
    assertThatThrownBy(() -> jdbc.update("update legacy_ranking_migration set " + column + "=" + column + "+1"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .satisfies(error -> {
          var cause = ((org.springframework.dao.DataIntegrityViolationException) error).getMostSpecificCause();
          assertThat(cause).isInstanceOf(SQLException.class);
          assertThat(((SQLException) cause).getSQLState()).isEqualTo("23514");
        });
    assertThat(snapshot(TARGETS)).isEqualTo(targetBefore);
    assertThat(migration.apply(plan, true).alreadyApplied()).isTrue();
    assertThat(snapshot(TARGETS)).isEqualTo(targetBefore);
    assertThat(snapshot(SOURCES)).isEqualTo(sourceBefore);
  }

  @Test void ratingInsertFailureRollsBackEveryEarlierDestinationWrite() {
    var plan = migration.preview(owner, rank); var before = snapshot(SOURCES);
    jdbc.execute("create function synthetic_rating_failure() returns trigger language plpgsql as $$ begin raise exception 'synthetic failure'; end; $$");
    jdbc.execute("create trigger synthetic_rating_failure before insert on ranking_movie_rating for each row execute function synthetic_rating_failure()");
    assertThatThrownBy(() -> migration.apply(plan, true)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertEmptyTargets(); assertThat(snapshot(SOURCES)).isEqualTo(before);
    jdbc.execute("drop trigger synthetic_rating_failure on ranking_movie_rating");
    assertThat(migration.apply(plan, true).alreadyApplied()).isFalse();
  }

  @Test void offlinePrimitiveHasNoSpringStereotypeOrProductionCaller() throws Exception {
    assertThat(org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(
        OfflineLegacyRankingMigration.class, org.springframework.stereotype.Component.class)).isFalse();
    try (var files = Files.walk(Path.of("src/main/java"))) {
      var callers = files.filter(p -> p.toString().endsWith(".java"))
          .filter(p -> !p.getFileName().toString().equals("OfflineLegacyRankingMigration.java"))
          .filter(p -> { try { return Files.readString(p).contains("OfflineLegacyRankingMigration"); }
                         catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); } }).toList();
      assertThat(callers).isEmpty();
    }
  }

  private UUID account(boolean admin) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into \"user\"(id,created_at,email,name,password) values (?,'2020-01-02T03:04:05.123456Z',?,'Synthetic','synthetic-hash')", id, id+"@example.invalid");
    jdbc.update("insert into user_roles(user_id,roles) values (?,?)", id, admin ? 1 : 2); return id;
  }
  private UUID movie(UUID creator, Boolean reveal) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into movie(id,created_at,updated_at,description,director,english_title,portuguese_title,user_id,show_notes) values (?,'2021-02-03T04:05:06.123456Z','2021-03-04T05:06:07.654321Z','synthetic','synthetic','synthetic','synthetic',?,?)", id, creator, reveal); return id;
  }
  private void rating(UUID movie, UUID author, Integer score) {
    jdbc.update("insert into movie_note(movie_id,user_id,note,created_at,updated_at) values (?,?,?,'2022-03-04T05:06:07.123456Z','2022-04-05T06:07:08.654321Z')", movie, author, score);
  }
  private Map<String,List<String>> snapshot(List<String> tables) {
    var result = new LinkedHashMap<String,List<String>>();
    for (String table : tables) result.put(table, jdbc.queryForList("select row_to_json(t)::text from "+table+" t order by row_to_json(t)::text", String.class));
    return result;
  }
  private void assertEmptyTargets() { snapshot(TARGETS).values().forEach(rows -> assertThat(rows).isEmpty()); }
  private void assertEquivalent(String left, String right) {
    assertThat(jdbc.queryForObject("select count(*) from (("+left+" except all "+right+") union all ("+right+" except all "+left+")) differences", Long.class)).isZero();
  }
}
