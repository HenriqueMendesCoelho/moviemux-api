package com.moviemux.migration;

import static org.assertj.core.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real V7-to-V8 upgrade; immutable migration provenance and terminal replay. */
class RankingEntryLifecycleMigrationIT {
  private String url, user, password, schema;
  private JdbcTemplate jdbc;
  private TransactionTemplate transactions;
  private DriverManagerDataSource source;
  private UUID owner, ranking, movie, entry, round, author;

  @BeforeEach void createDisposableSchema() throws Exception {
    url = System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    schema = "entry_lifecycle_migration_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, user, password)) {
      connection.createStatement().execute("create schema " + schema);
    }
    var dataSource = new DriverManagerDataSource(url, user, password) {
      @Override public Connection getConnection() throws SQLException {
        var connection = super.getConnection();
        connection.setSchema(schema);
        try (var statement=connection.createStatement()) { statement.execute("set timezone='UTC'"); }
        return connection;
      }
    };
    source = dataSource;
    jdbc = new JdbcTemplate(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @AfterEach void removeOnlyOwnedSchema() throws Exception {
    if (schema != null) try (var connection = DriverManager.getConnection(url, user, password)) {
      connection.createStatement().execute("drop schema " + schema + " cascade");
    }
  }

  @Test void v7UpgradePreservesOriginalEvidenceAndStableReplayThenPurgeClosesItForever() throws Exception {
    migrate("7"); fixtureV7();
    var oldMap=jdbc.queryForMap("select * from legacy_ranking_movie_map");
    var oldLedger=jdbc.queryForMap("select * from legacy_ranking_migration");
    var oldSource=jdbc.queryForList("select row_to_json(n)::text from movie_note n",String.class);
    var legacyCatalog=legacyCatalog();
    assertThat(migrate("8")).isEqualTo(1);
    assertThat(legacyCatalog()).containsExactlyElementsOf(legacyCatalog);
    assertThat(jdbc.queryForMap("select migration_key,ranking_id,legacy_movie_id,entry_id,round_id from legacy_ranking_movie_map")).isEqualTo(oldMap);
    var currentLedger=jdbc.queryForMap("select * from legacy_ranking_migration");currentLedger.remove("replay_closed_at");
    assertThat(currentLedger).isEqualTo(oldLedger);
    assertThat(jdbc.queryForMap("select live_entry_id,live_round_id from legacy_ranking_movie_map"))
        .containsEntry("live_entry_id",entry).containsEntry("live_round_id",round);
    assertThat(jdbc.queryForObject("select removed_at from ranking_movie_entry",Timestamp.class)).isNull();
    for(String sql:List.of("update legacy_ranking_movie_map set live_entry_id=null",
        "update legacy_ranking_movie_map set live_round_id=gen_random_uuid()",
        "update legacy_ranking_movie_map set round_id=gen_random_uuid()",
        "update legacy_ranking_migration set source_movies=source_movies+1"))
      assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);

    assertThat(jdbc.queryForObject("select lifecycle_version from ranking_movie_entry",Long.class)).isZero();
    assertThat(migrate("8")).isZero();
    var offline=new com.moviemux.ranking.adapter.repository.postgres.OfflineLegacyRankingMigration(source,100);
    var plan=offline.preview(owner,ranking);
    assertThat(offline.apply(plan,true).alreadyApplied()).isTrue();
    assertThat(jdbc.queryForObject("select target_fingerprint from legacy_ranking_migration",String.class)).isEqualTo(oldLedger.get("target_fingerprint"));

    assertThatThrownBy(()->jdbc.update("update ranking_movie_entry set removed_at=now()"))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update("update ranking_movie_entry set removed_at=now(),removal_token=?",UUID.randomUUID());
    assertThatThrownBy(()->offline.preview(owner,ranking)).hasMessage("migration.already_applied_different_state");
    jdbc.update("update ranking_movie_entry set removed_at=null,removal_token=null");
    assertThat(offline.apply(plan,true).alreadyApplied()).isTrue();
    jdbc.update("update ranking_movie_entry set lifecycle_version=2");
    assertThatThrownBy(()->offline.apply(plan,true)).hasMessage("migration.already_applied_different_state");
    assertThatThrownBy(()->jdbc.update("update ranking_movie_entry set lifecycle_version=-1")).isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(()->transactions.executeWithoutResult(status->
        jdbc.update("update legacy_ranking_movie_map set live_entry_id=null,live_round_id=null,purged_at=now()")))
        .isInstanceOf(RuntimeException.class);
    assertThat(jdbc.queryForObject("select purged_at from legacy_ranking_movie_map",Timestamp.class)).isNull();

    transactions.executeWithoutResult(status->{
      jdbc.update("update legacy_ranking_migration set replay_closed_at=now()");
      jdbc.update("update legacy_ranking_movie_map set live_entry_id=null,live_round_id=null,purged_at=now()");
      jdbc.update("delete from ranking_movie_rating where ranking_id=? and round_id=?",ranking,round);
      jdbc.update("delete from ranking_movie_round where ranking_id=? and round_id=?",ranking,round);
      jdbc.update("delete from ranking_movie_entry where ranking_id=? and entry_id=?",ranking,entry);
    });
    assertThat(jdbc.queryForMap("select migration_key,ranking_id,legacy_movie_id,entry_id,round_id from legacy_ranking_movie_map")).isEqualTo(oldMap);
    currentLedger=jdbc.queryForMap("select * from legacy_ranking_migration");assertThat(currentLedger.remove("replay_closed_at")).isNotNull();
    assertThat(currentLedger).isEqualTo(oldLedger);
    assertThat(jdbc.queryForList("select row_to_json(n)::text from movie_note n",String.class)).containsExactlyElementsOf(oldSource);
    assertThat(jdbc.queryForObject("select count(*) from movie",Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from ranking_author_identity",Integer.class)).isEqualTo(1);
    assertThatThrownBy(()->offline.apply(plan,true)).hasMessage("migration.replay_closed_after_context_purge");
    assertThatThrownBy(()->offline.preview(owner,ranking)).hasMessage("migration.replay_closed_after_context_purge");
    for(String sql:List.of("update legacy_ranking_migration set replay_closed_at=null",
        "update legacy_ranking_migration set replay_closed_at=replay_closed_at+interval '1 second'",
        "update legacy_ranking_movie_map set purged_at=null,live_entry_id=entry_id,live_round_id=round_id",
        "update legacy_ranking_movie_map set entry_id=gen_random_uuid()",
        "delete from legacy_ranking_movie_map","delete from legacy_ranking_migration"))
      assertThatThrownBy(()->jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_entry",Integer.class)).isZero();
  }

  private void fixtureV7() throws Exception {
    owner=UUID.randomUUID();ranking=UUID.randomUUID();movie=UUID.randomUUID();entry=UUID.randomUUID();round=UUID.randomUUID();author=UUID.randomUUID();
    transactions.executeWithoutResult(status->{
      jdbc.update("insert into \"user\"(id,created_at,email,name,password) values (?,now(),?,'Synthetic','synthetic')",owner,owner+"@example.invalid");
      jdbc.update("insert into movie(id,created_at,description,director,english_title,portuguese_title,user_id,show_notes) values (?,now(),'Synthetic','Synthetic','Synthetic','Synthetic',?,false)",movie,owner);
      jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),8)",movie,owner);
      jdbc.update("insert into ranking(id,owner_user_id,visibility,admission,created_at) values (?,?,'PRIVATE','INVITATION',now())",ranking,owner);
      jdbc.update("insert into ranking_membership(ranking_id,user_id,active,created_at) values (?,?,true,now())",ranking,owner);
      for(String grant:List.of("READ","RATE_OWN","ADD_ENTRY","REMOVE_OWN_ENTRY")) jdbc.update("insert into ranking_membership_grant values (?,?,?)",ranking,owner,grant);
      jdbc.update("insert into ranking_author_identity(ranking_id,author_id,account_id,deleted_public_code) values (?,?,?,?)",ranking,author,owner,UUID.randomUUID());
      jdbc.update("insert into ranking_movie_entry select ?,?,id,?,created_at,updated_at from movie",ranking,entry,author);
      jdbc.update("insert into ranking_movie_round select ?,?,?,1,null,created_at,updated_at,show_notes from movie",ranking,entry,round);
      jdbc.update("insert into ranking_movie_rating select ?,?,?,note,created_at,updated_at from movie_note",ranking,round,author);
      jdbc.update("insert into legacy_ranking_migration values ('legacy-movies-v1',?,?,1,?,?,1,1,1,now())",ranking,owner,"0".repeat(64),"0".repeat(64));
      jdbc.update("insert into legacy_ranking_movie_map values ('legacy-movies-v1',?,?,?,?)",ranking,movie,entry,round);
    });
    var sourceHash=java.security.MessageDigest.getInstance("SHA-256");
    feed(sourceHash,"legacy-movies-v1");feed(sourceHash,owner.toString());feed(sourceHash,ranking.toString());
    hashQuery(sourceHash,"users","select row_to_json(s)::text from (select id,created_at from \"user\" order by id) s");
    hashQuery(sourceHash,"movies","select row_to_json(s)::text from (select * from movie order by id) s");
    hashQuery(sourceHash,"ratings","select row_to_json(s)::text from (select * from movie_note order by movie_id,user_id) s");
    var targetHash=java.security.MessageDigest.getInstance("SHA-256");
    for(String table:List.of("ranking","ranking_membership","ranking_membership_grant","ranking_author_identity","ranking_movie_entry","ranking_movie_round","ranking_movie_rating","legacy_ranking_movie_map"))
      hashQuery(targetHash,table,"select row_to_json(s)::text from "+table+" s order by row_to_json(s)::text");
    jdbc.update("update legacy_ranking_migration set source_fingerprint=?,target_fingerprint=?",java.util.HexFormat.of().formatHex(sourceHash.digest()),java.util.HexFormat.of().formatHex(targetHash.digest()));
  }
  private void feed(java.security.MessageDigest hash,String text) {
    byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());hash.update(bytes);
  }
  private void hashQuery(java.security.MessageDigest hash,String label,String sql) {
    feed(hash,label);for(String row:jdbc.queryForList(sql,String.class)) feed(hash,row);
  }
  private int migrate(String target) {
    return Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
        .createSchemas(false).cleanDisabled(true).target(target).load().migrate().migrationsExecuted;
  }
  private List<String> legacyCatalog() {
    var result=new ArrayList<String>();
    for(String sql:List.of(
      "select table_name||':'||column_name||':'||data_type||':'||is_nullable||':'||coalesce(column_default,'') from information_schema.columns where table_schema=? and table_name not like 'ranking%' and table_name not like 'legacy_%' and table_name<>'flyway_schema_history' order by table_name,ordinal_position",
      "select c.relname||':'||con.conname||':'||pg_get_constraintdef(con.oid) from pg_constraint con join pg_class c on c.oid=con.conrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname=? and c.relname not like 'ranking%' and c.relname not like 'legacy_%' and c.relname<>'flyway_schema_history' order by c.relname,con.conname")) result.addAll(jdbc.queryForList(sql,String.class,schema));
    return result;
  }
}
