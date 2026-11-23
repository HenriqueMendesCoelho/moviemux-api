package com.moviemux.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.moviemux.ranking.adapter.repository.postgres.OfflineLegacyRankingMigration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class RankingNameMigrationIT {
  private String url, user, password, schema;
  private DriverManagerDataSource source;
  private JdbcTemplate jdbc;

  @BeforeEach void createDisposableSchema() throws Exception {
    url = System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    schema = "ranking_name_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, user, password);
         var statement = connection.createStatement()) {
      statement.execute("create schema " + schema);
    }
    source = new DriverManagerDataSource(url, user, password) {
      @Override public Connection getConnection() throws SQLException {
        var connection = super.getConnection();
        connection.setSchema(schema);
        return connection;
      }
    };
    jdbc = new JdbcTemplate(source);
  }

  @AfterEach void removeOwnedSchema() throws Exception {
    if (schema != null) try (var connection = DriverManager.getConnection(url, user, password);
                            var statement = connection.createStatement()) {
      statement.execute("drop schema " + schema + " cascade");
    }
  }

  @Test void v8OfflineCopyReplaysUnchangedAfterV9AddsNullableName() {
    assertThat(flyway("8").migrate().migrationsExecuted).isEqualTo(8);
    UUID owner = UUID.randomUUID(), ranking = UUID.randomUUID(), movie = UUID.randomUUID();
    jdbc.update("""
        insert into "user"(id,created_at,email,name,password)
        values (?,now(),?,'Synthetic','synthetic')
        """, owner, owner + "@example.invalid");
    jdbc.update("""
        insert into movie(id,created_at,description,director,english_title,portuguese_title,user_id,show_notes)
        values (?,now(),'Synthetic','Synthetic','Synthetic','Synthetic',?,false)
        """, movie, owner);
    jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),8)", movie, owner);

    var offlineBefore = new OfflineLegacyRankingMigration(source, 100);
    var originalPlan = offlineBefore.preview(owner, ranking);
    assertThat(offlineBefore.apply(originalPlan, true))
        .isEqualTo(new OfflineLegacyRankingMigration.Result(false, 1, 1, 1));
    assertThat(offlineBefore.apply(originalPlan, true))
        .isEqualTo(new OfflineLegacyRankingMigration.Result(true, 1, 1, 1));
    var priorRows = snapshotWithoutName();
    String priorFingerprint = jdbc.queryForObject("select target_fingerprint from legacy_ranking_migration", String.class);
    assertThat(priorFingerprint).matches("[0-9a-f]{64}").isNotEqualTo("0".repeat(64));
    assertThat(nameColumn()).isEmpty();

    var v9 = flyway("9");
    assertThat(v9.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(v9.info().current().getVersion().getVersion()).isEqualTo("9");
    assertThat(nameColumn()).singleElement().satisfies(column -> assertThat(column)
        .containsEntry("data_type", "character varying")
        .containsEntry("character_maximum_length", 120)
        .containsEntry("is_nullable", "YES")
        .containsEntry("column_default", null));
    assertThat(jdbc.queryForObject("select name from ranking where id=?", String.class, ranking)).isNull();
    assertThat(snapshotWithoutName()).isEqualTo(priorRows);

    var offlineAfter = new OfflineLegacyRankingMigration(source, 100);
    assertThat(offlineAfter.preview(owner, ranking)).isEqualTo(originalPlan);
    assertThat(offlineAfter.apply(originalPlan, true))
        .isEqualTo(new OfflineLegacyRankingMigration.Result(true, 1, 1, 1));
    assertThat(snapshotWithoutName()).isEqualTo(priorRows);
    assertThat(jdbc.queryForObject("select target_fingerprint from legacy_ranking_migration", String.class))
        .isEqualTo(priorFingerprint);
    assertThat(jdbc.queryForObject(
        "select count(*) from flyway_schema_history where version='9' and type='SQL' and success", Integer.class))
        .isEqualTo(1);
    assertThat(v9.migrate().migrationsExecuted).isZero();
  }

  private Flyway flyway(String target) {
    return Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
        .createSchemas(false).baselineOnMigrate(false).cleanDisabled(true).target(target).load();
  }

  private List<Map<String, Object>> nameColumn() {
    return jdbc.queryForList("""
        select data_type,character_maximum_length,is_nullable,column_default
        from information_schema.columns where table_schema=? and table_name='ranking' and column_name='name'
        """, schema);
  }

  private Map<String, List<String>> snapshotWithoutName() {
    var rows = new TreeMap<String, List<String>>();
    for (String table : List.of("\"user\"", "movie", "movie_note", "ranking", "ranking_membership",
        "ranking_membership_grant", "ranking_author_identity", "ranking_movie_entry", "ranking_movie_round",
        "ranking_movie_rating", "legacy_ranking_migration", "legacy_ranking_movie_map")) {
      String json = table.equals("ranking") ? "(to_jsonb(r) - 'name')::text" : "to_jsonb(r)::text";
      rows.put(table, jdbc.queryForList("select " + json + " from " + table + " r order by 1", String.class));
    }
    return rows;
  }
}
