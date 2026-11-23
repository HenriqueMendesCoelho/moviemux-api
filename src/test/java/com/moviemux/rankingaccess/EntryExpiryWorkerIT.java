package com.moviemux.rankingaccess;

import static org.assertj.core.api.Assertions.*;

import com.moviemux.ranking.adapter.repository.postgres.OfflineLegacyRankingMigration;
import com.moviemux.rankingcontent.adapter.repository.postgres.EntryExpiryWorker;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class EntryExpiryWorkerIT {
  private String url,user,password,schema;
  private DriverManagerDataSource source;
  private JdbcTemplate jdbc;
  private UUID owner,rank;
  private OfflineLegacyRankingMigration migration;
  private OfflineLegacyRankingMigration.Plan plan;
  private EntryExpiryWorker worker;

  @BeforeEach void fixture() throws Exception {
    url=System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user=System.getenv().getOrDefault("TEST_DATABASE_USER","moviemux_test");
    password=System.getenv().getOrDefault("TEST_DATABASE_PASSWORD","moviemux_test");
    schema="expiry_worker_test_"+UUID.randomUUID().toString().replace("-","");
    try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){s.execute("create schema "+schema);}
    source=new DriverManagerDataSource(url,user,password){
      @Override public Connection getConnection() throws SQLException {
        var c=super.getConnection();c.setSchema(schema);return c;
      }
    };
    jdbc=new JdbcTemplate(source);
    Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
        .createSchemas(false).cleanDisabled(true).target("16").load().migrate();
    owner=UUID.randomUUID();rank=UUID.randomUUID();
    jdbc.update("insert into \"user\"(id,created_at,email,name,password) values (?,now(),?,'Synthetic','synthetic')",owner,owner+"@example.invalid");
    for(int i=0;i<2;i++){
      UUID movie=UUID.randomUUID();
      jdbc.update("""
          insert into movie(id,created_at,description,director,english_title,portuguese_title,user_id,show_notes)
          values (?,now(),'Synthetic','Synthetic','Synthetic','Synthetic',?,false)
          """,movie,owner);
      jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),?)",movie,owner,i==0?null:12);
    }
    migration=new OfflineLegacyRankingMigration(source,100);
    plan=migration.preview(owner,rank);migration.apply(plan,true);
    worker=new EntryExpiryWorker(source);
  }

  @AfterEach void cleanup() throws Exception {
    if(schema!=null)try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){s.execute("drop schema "+schema+" cascade");}
  }

  @Test void boundedPurgePreservesSourceCatalogAuthorsAndTerminalProvenance() {
    var sourceBefore=snapshot("\"user\"","movie","movie_note","ranking_author_identity","ranking_membership","ranking_membership_grant");
    var ledgerBefore=jdbc.queryForMap("select source_fingerprint,target_fingerprint,source_users,source_movies,source_ratings,completed_at from legacy_ranking_migration");
    var mapBefore=jdbc.queryForList("select entry_id,round_id,legacy_movie_id from legacy_ranking_movie_map order by entry_id");
    expireAll();
    assertThat(worker.runBatch(1)).isEqualTo(1);
    assertThat(count("ranking_movie_entry")).isEqualTo(1);
    assertThat(count("ranking_movie_round")).isEqualTo(1);
    assertThat(count("ranking_movie_rating")).isEqualTo(1);
    var closedLedger=jdbc.queryForMap("select * from legacy_ranking_migration");
    assertThat(closedLedger.get("replay_closed_at")).isNotNull();
    assertThat(worker.runBatch(100)).isEqualTo(1);
    assertThat(jdbc.queryForMap("select * from legacy_ranking_migration")).isEqualTo(closedLedger);
    assertThat(worker.runBatch(100)).isZero();
    assertThat(snapshot("\"user\"","movie","movie_note","ranking_author_identity","ranking_membership","ranking_membership_grant")).isEqualTo(sourceBefore);
    assertThat(jdbc.queryForMap("select source_fingerprint,target_fingerprint,source_users,source_movies,source_ratings,completed_at from legacy_ranking_migration")).isEqualTo(ledgerBefore);
    assertThat(jdbc.queryForList("select entry_id,round_id,legacy_movie_id from legacy_ranking_movie_map order by entry_id")).isEqualTo(mapBefore);
    assertThat(jdbc.queryForObject("select count(*) from legacy_ranking_movie_map where purged_at is not null and live_entry_id is null and live_round_id is null",Long.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("select replay_closed_at is not null from legacy_ranking_migration",Boolean.class)).isTrue();
    assertThatThrownBy(()->migration.apply(plan,true)).isInstanceOf(IllegalStateException.class);
    assertThat(count("ranking_movie_rating")).isZero();
  }

  @Test void multipleMappedEntriesInOneBatchRetainSingleImmutableClosure() {
    expireAll();
    assertThat(worker.runBatch(100)).isEqualTo(2);
    var closedLedger=jdbc.queryForMap("select * from legacy_ranking_migration");
    assertThat(closedLedger.get("replay_closed_at")).isNotNull();
    assertThat(jdbc.queryForObject("select count(*) from legacy_ranking_movie_map where purged_at is not null",Long.class)).isEqualTo(2);
    assertThat(count("ranking_movie_entry")).isZero();
    assertThat(worker.runBatch(100)).isZero();
    assertThat(jdbc.queryForMap("select * from legacy_ranking_migration")).isEqualTo(closedLedger);
  }

  @Test void unmappedEntryPurgesWithoutClosingUnrelatedMigrationOrDeletingCatalog() {
    UUID movie=UUID.randomUUID(),entry=UUID.randomUUID(),round=UUID.randomUUID();
    jdbc.update("insert into movie(id,created_at,description,director,english_title,portuguese_title) values (?,now(),'Synthetic','Synthetic','Synthetic','Synthetic')",movie);
    jdbc.update("insert into ranking_movie_entry(ranking_id,entry_id,movie_id,created_at,removed_at,removal_token) values (?,?,?,now(),now()-interval '25 hours',?)",rank,entry,movie,UUID.randomUUID());
    jdbc.update("insert into ranking_movie_round(ranking_id,entry_id,round_id,round_number,created_at,revealed) values (?,?,?,1,now(),false)",rank,entry,round);
    var before=snapshot("legacy_ranking_movie_map","legacy_ranking_migration","movie","movie_note");
    assertThat(worker.runBatch(100)).isEqualTo(1);
    assertThat(snapshot("legacy_ranking_movie_map","legacy_ranking_migration","movie","movie_note")).isEqualTo(before);
    assertThat(count("ranking_movie_entry")).isEqualTo(2);
    assertThat(count("ranking_movie_round")).isEqualTo(2);
  }

  @Test void activeAndWithinRetentionEntriesRemainUntouchedAndInvalidBoundsFail() {
    UUID entry=entries().get(0);
    jdbc.update("update ranking_movie_entry set removed_at=clock_timestamp()-interval '23 hours',removal_token=? where entry_id=?",UUID.randomUUID(),entry);
    var before=snapshot("ranking_movie_entry","ranking_movie_round","ranking_movie_rating","legacy_ranking_movie_map","legacy_ranking_migration");
    assertThat(worker.runBatch(100)).isZero();
    assertThatThrownBy(()->worker.runBatch(0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->worker.runBatch(101)).isInstanceOf(IllegalArgumentException.class);
    assertThat(snapshot("ranking_movie_entry","ranking_movie_round","ranking_movie_rating","legacy_ranking_movie_map","legacy_ranking_migration")).isEqualTo(before);
  }

  @Test void busyRankingIsSkippedAndCommittedRestoreCannotBePurged() throws Exception {
    expireAll();
    try(var c=source.getConnection()){
      c.setAutoCommit(false);
      try(var s=c.prepareStatement("select id from ranking where id=? for update")){s.setObject(1,rank);s.executeQuery().close();}
      var future=CompletableFuture.supplyAsync(()->worker.runBatch(100));
      assertThat(future.get(10,TimeUnit.SECONDS)).isZero();
      try(var s=c.prepareStatement("update ranking_movie_entry set removed_at=null,removal_token=null where ranking_id=?")){s.setObject(1,rank);s.executeUpdate();}
      c.commit();
    }
    assertThat(worker.runBatch(100)).isZero();
    assertThat(count("ranking_movie_entry")).isEqualTo(2);
    assertThat(count("ranking_movie_rating")).isEqualTo(2);
    assertThat(jdbc.queryForObject("select replay_closed_at is null from legacy_ranking_migration",Boolean.class)).isTrue();
  }

  @Test void failureRollsBackTerminalReceiptAndAllDependentDeletes() {
    expireAll();
    var before=snapshot("ranking_movie_entry","ranking_movie_round","ranking_movie_rating","legacy_ranking_movie_map","legacy_ranking_migration");
    jdbc.execute("create function reject_expiry_test() returns trigger language plpgsql as $$ begin raise exception 'synthetic expiry failure'; end; $$");
    jdbc.execute("create trigger reject_expiry_test before delete on ranking_movie_entry for each row execute function reject_expiry_test()");
    assertThatThrownBy(()->worker.runBatch(1)).isInstanceOf(RuntimeException.class);
    assertThat(snapshot("ranking_movie_entry","ranking_movie_round","ranking_movie_rating","legacy_ranking_movie_map","legacy_ranking_migration")).isEqualTo(before);
    jdbc.execute("drop trigger reject_expiry_test on ranking_movie_entry");
    assertThat(worker.runBatch(100)).isEqualTo(2);
  }

  private List<UUID> entries(){return jdbc.queryForList("select entry_id from ranking_movie_entry order by entry_id",UUID.class);}
  private void expireAll(){jdbc.update("update ranking_movie_entry set removed_at=clock_timestamp()-interval '25 hours',removal_token=?",UUID.randomUUID());}
  private long count(String table){return Objects.requireNonNull(jdbc.queryForObject("select count(*) from "+table,Long.class));}
  private Map<String,List<String>> snapshot(String... tables){
    var result=new LinkedHashMap<String,List<String>>();
    for(String table:tables)result.put(table,jdbc.queryForList("select row_to_json(t)::text from "+table+" t order by row_to_json(t)::text",String.class));
    return result;
  }
}
