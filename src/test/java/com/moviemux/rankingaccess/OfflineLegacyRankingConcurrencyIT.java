package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.*;

import com.moviemux.ranking.adapter.repository.postgres.OfflineLegacyRankingMigration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** PostgreSQL lock barriers, never timing-only assumptions or production data. */
class OfflineLegacyRankingConcurrencyIT {
  private static final List<String> TARGETS = List.of("ranking", "ranking_membership",
      "ranking_membership_grant", "ranking_author_identity", "ranking_movie_entry",
      "ranking_movie_round", "ranking_movie_rating", "legacy_ranking_migration", "legacy_ranking_movie_map");
  private String url, user, password, schema;
  private DriverManagerDataSource source;
  private JdbcTemplate jdbc;
  private OfflineLegacyRankingMigration migration;
  private UUID owner, movie, rank;

  @BeforeEach void isolatedFixture() throws Exception {
    url=System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user=System.getenv().getOrDefault("TEST_DATABASE_USER","moviemux_test");
    password=System.getenv().getOrDefault("TEST_DATABASE_PASSWORD","moviemux_test");
    schema="legacy_concurrency_test_"+UUID.randomUUID().toString().replace("-","");
    try(var connection=DriverManager.getConnection(url,user,password)) {
      connection.createStatement().execute("create schema "+schema);
    }
    source=new DriverManagerDataSource(url,user,password) {
      @Override public Connection getConnection() throws SQLException {
        var connection=super.getConnection();connection.setSchema(schema);return connection;
      }
    };
    jdbc=new JdbcTemplate(source);
    Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
        .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).target("8").load().migrate();
    migration=new OfflineLegacyRankingMigration(source,100);
    owner=UUID.randomUUID();movie=UUID.randomUUID();rank=UUID.randomUUID();
    jdbc.update("insert into \"user\"(id,created_at,email,name,password) values (?,now(),?,'Synthetic','synthetic-hash')",owner,owner+"@example.invalid");
    jdbc.update("insert into movie(id,created_at,description,director,english_title,portuguese_title,user_id,show_notes) values (?,now(),'Synthetic','Synthetic','Synthetic','Synthetic',?,null)",movie,owner);
    jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),null)",movie,owner);
  }

  @AfterEach void removeOnlyOwnedSchema() throws Exception {
    if(schema!=null) try(var connection=DriverManager.getConnection(url,user,password)) {
      connection.createStatement().execute("drop schema "+schema+" cascade");
    }
  }

  @Test void twoAppliesReallyWaitOnLedgerThenOneAppliesAndOneReplaysWithoutDuplicates() throws Exception {
    var plan=migration.preview(owner,rank);
    var before=snapshot(List.of("\"user\"","movie","movie_note"));
    var pool=Executors.newFixedThreadPool(2);
    try(var barrier=source.getConnection()) {
      barrier.setAutoCommit(false);
      barrier.createStatement().execute("lock table legacy_ranking_migration in share row exclusive mode");
      var first=pool.submit(() -> migration.apply(plan,true));
      var second=pool.submit(() -> migration.apply(plan,true));
      // Both independent applies must reach the real PostgreSQL ledger lock before release.
      awaitWaitingLocks("legacy_ranking_migration","ShareRowExclusiveLock",2);
      assertThat(first.isDone()).isFalse();assertThat(second.isDone()).isFalse();
      barrier.commit();
      var a=first.get(20,TimeUnit.SECONDS);var b=second.get(20,TimeUnit.SECONDS);
      assertThat(List.of(a.alreadyApplied(),b.alreadyApplied())).containsExactlyInAnyOrder(false,true);
      assertThat(a.users()).isEqualTo(1);assertThat(a.movies()).isEqualTo(1);assertThat(a.ratings()).isEqualTo(1);
      assertThat(b.users()).isEqualTo(a.users());assertThat(b.movies()).isEqualTo(a.movies());assertThat(b.ratings()).isEqualTo(a.ratings());
      for(String table:TARGETS) {
        int expected=table.equals("ranking_membership_grant")?4:1;
        assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isEqualTo(expected);
      }
      var stable=snapshot(TARGETS);
      assertThat(migration.apply(plan,true).alreadyApplied()).isTrue();
      assertThat(snapshot(TARGETS)).containsExactlyElementsOf(stable);
      assertThat(snapshot(List.of("\"user\"","movie","movie_note"))).containsExactlyElementsOf(before);
    } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(20,TimeUnit.SECONDS)).isTrue(); }
  }

  @Test void applyWaitsForSourceWriterThenRejectsChangedCommittedSnapshotWithoutAnyDestination() throws Exception {
    var plan=migration.preview(owner,rank);
    var pool=Executors.newSingleThreadExecutor();
    try(var writer=source.getConnection()) {
      writer.setAutoCommit(false);
      try(var update=writer.prepareStatement("update movie set show_notes=false where id=?")) {
        update.setObject(1,movie);assertThat(update.executeUpdate()).isEqualTo(1);
      }
      var pending=pool.submit(() -> migration.apply(plan,true));
      awaitWaitingLocks("movie","ShareLock",1);
      assertThat(pending.isDone()).isFalse();
      writer.commit();
      assertThatThrownBy(() -> pending.get(20,TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class).hasRootCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage("migration.source_changed");
      for(String table:TARGETS)
        assertThat(jdbc.queryForObject("select count(*) from "+table,Integer.class)).as(table).isZero();
      assertThat(jdbc.queryForObject("select show_notes from movie where id=?",Boolean.class,movie)).isFalse();
      assertThat(jdbc.queryForObject("select note from movie_note where movie_id=?",Integer.class,movie)).isNull();
      assertThat(migration.preview(owner,rank).fingerprint()).isNotEqualTo(plan.fingerprint());
    } finally { pool.shutdownNow(); assertThat(pool.awaitTermination(20,TimeUnit.SECONDS)).isTrue(); }
  }

  private void awaitWaitingLocks(String relation,String mode,int required) throws InterruptedException {
    // Migration deliberately has a five-second lock timeout; observe promptly, then release.
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
    while(System.nanoTime()<deadline) {
      Integer waiting=jdbc.queryForObject("""
          select count(distinct activity.pid) from pg_stat_activity activity
          join pg_locks waiting on waiting.pid=activity.pid
          where activity.datname=current_database() and activity.wait_event_type='Lock'
            and cardinality(pg_blocking_pids(activity.pid))>0
            and not waiting.granted and waiting.relation=cast(? as regclass) and waiting.mode=?
          """,Integer.class,schema+"."+relation,mode);
      if(waiting!=null && waiting>=required)return;
      Thread.sleep(15);
    }
    throw new AssertionError("Migration did not reach expected PostgreSQL lock barrier: "+relation);
  }

  private List<String> snapshot(List<String> tables) {
    var result=new ArrayList<String>();
    for(String table:tables)
      result.addAll(jdbc.queryForList("select row_to_json(t)::text from "+table+" t order by row_to_json(t)::text",String.class));
    return result;
  }
}
