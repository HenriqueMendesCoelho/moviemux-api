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

/** Real V2-to-V3 upgrade and terminal archive constraints, using synthetic data only. */
class RankingArchiveMigrationIT {
  private String url, user, password, schema;
  private JdbcTemplate jdbc;
  private TransactionTemplate transactions;

  @BeforeEach void createDisposableSchema() throws Exception {
    url = System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    schema = "archive_migration_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, user, password)) {
      connection.createStatement().execute("create schema " + schema);
    }
    var dataSource = new DriverManagerDataSource(url, user, password) {
      @Override public Connection getConnection() throws SQLException {
        var connection = super.getConnection();
        connection.setSchema(schema);
        return connection;
      }
    };
    jdbc = new JdbcTemplate(dataSource);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
  }

  @AfterEach void removeOnlyOwnedSchema() throws Exception {
    if (schema != null) try (var connection = DriverManager.getConnection(url, user, password)) {
      connection.createStatement().execute("drop schema " + schema + " cascade");
    }
  }

  @Test void v2UpgradeAddsOnlyArchiveColumnAndPreservesExistingTablesAndData() {
    assertThat(migrate("2")).isEqualTo(2);
    UUID ranking = insertSyntheticHistory();
    var oldTables = tables();
    assertThat(oldTables).hasSize(16);
    var oldCatalog = catalog();
    var oldData = historyRows();

    assertThat(migrate(null)).isEqualTo(1);
    assertThat(tables()).containsExactlyElementsOf(oldTables);
    var expectedCatalog = new ArrayList<>(oldCatalog);
    expectedCatalog.add("column:ranking:archived_at:timestamp with time zone:timestamptz::YES::NO:");
    assertThat(catalog()).containsExactlyInAnyOrderElementsOf(expectedCatalog);
    assertThat(historyRows()).isEqualTo(oldData);
    assertThat(jdbc.queryForObject("select archived_at from ranking where id=?", Timestamp.class, ranking)).isNull();
    assertThat(jdbc.queryForObject("select count(*) from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname=? and c.relname='ranking' and t.tgname='ranking_archive_is_terminal' and not t.tgisinternal", Integer.class, schema)).isEqualTo(1);
    assertThat(jdbc.queryForList("select version||':'||type||':'||success::text from flyway_schema_history order by installed_rank", String.class))
        .containsExactly("1:SQL:true", "2:SQL:true", "3:SQL:true");

    var after = catalog();
    assertThat(migrate(null)).isZero();
    assertThat(catalog()).containsExactlyElementsOf(after);
    assertThat(historyRows()).isEqualTo(oldData);
    assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='3' and success", Integer.class)).isEqualTo(1);
  }

  @Test void archiveTimestampCannotBeClearedOrRewrittenAndNeverDeletesHistory() {
    assertThat(migrate(null)).isEqualTo(3);
    UUID ranking = insertSyntheticHistory();
    var before = historyRows();
    assertThat(jdbc.queryForObject("select archived_at from ranking where id=?", Timestamp.class, ranking)).isNull();
    Instant archivedAt = Instant.parse("2026-11-17T12:00:00Z");
    jdbc.update("update ranking set archived_at=? where id=?", Timestamp.from(archivedAt), ranking);

    assertArchiveRejected(() -> jdbc.update("update ranking set archived_at=null where id=?", ranking));
    assertArchiveRejected(() -> jdbc.update("update ranking set archived_at=? where id=?", Timestamp.from(archivedAt.plusSeconds(1)), ranking));
    assertThat(jdbc.queryForObject("select archived_at from ranking where id=?", Timestamp.class, ranking).toInstant()).isEqualTo(archivedAt);
    assertThat(jdbc.update("update ranking set archived_at=archived_at where id=?", ranking)).isEqualTo(1);
    assertThat(historyRows()).isEqualTo(before);
    assertThat(jdbc.queryForObject("select count(*) from movie_note where note=8", Integer.class)).isEqualTo(1);
    assertThat(migrate(null)).isZero();
    assertThat(jdbc.queryForObject("select archived_at from ranking where id=?", Timestamp.class, ranking).toInstant()).isEqualTo(archivedAt);
    assertThat(historyRows()).isEqualTo(before);
  }

  private void assertArchiveRejected(Runnable update) {
    assertThatThrownBy(update::run).isInstanceOf(DataIntegrityViolationException.class).satisfies(error -> {
      var cause = ((DataIntegrityViolationException) error).getMostSpecificCause();
      assertThat(cause).isInstanceOf(SQLException.class);
      assertThat(((SQLException) cause).getSQLState()).isEqualTo("23514");
    });
  }

  private UUID insertSyntheticHistory() {
    UUID owner = UUID.randomUUID(), ranking = UUID.randomUUID(), movie = UUID.randomUUID();
    transactions.executeWithoutResult(status -> {
      jdbc.update("insert into \"user\"(id,created_at,email,name,password) values (?,now(),?,'Synthetic archive owner','synthetic-hash')", owner, owner+"@example.invalid");
      jdbc.update("insert into ranking(id,owner_user_id,visibility,admission,created_at) values (?,?,'PRIVATE','INVITATION',now())", ranking, owner);
      jdbc.update("insert into ranking_membership(ranking_id,user_id,active,created_at) values (?,?,true,now())", ranking, owner);
      jdbc.update("insert into ranking_membership_grant(ranking_id,user_id,capability) values (?,?,'READ')", ranking, owner);
      jdbc.update("insert into movie(id,created_at,description,director,english_title,portuguese_title,user_id) values (?,now(),'Synthetic archive history','Synthetic','Synthetic','Synthetic',?)", movie, owner);
      jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),8)", movie, owner);
    });
    return ranking;
  }

  private int migrate(String target) {
    var config = Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
        .createSchemas(false).baselineOnMigrate(false).cleanDisabled(true).locations("classpath:db/migration");
    if (target != null) config.target(target);
    return config.load().migrate().migrationsExecuted;
  }

  private List<String> tables() {
    return jdbc.queryForList("select table_name from information_schema.tables where table_schema=? and table_name<>'flyway_schema_history' order by table_name", String.class, schema);
  }

  private Map<String, List<String>> historyRows() {
    Map<String, List<String>> rows = new TreeMap<>();
    for (String table : List.of("\"user\"", "movie", "movie_note", "ranking_membership", "ranking_membership_grant")) {
      rows.put(table, jdbc.queryForList("select row_to_json(t)::text from " + table + " t order by 1", String.class));
    }
    // Deliberately enumerate every V2 ranking column; V3 adds only archived_at.
    rows.put("ranking", jdbc.queryForList("select row_to_json(t)::text from (select id,owner_user_id,visibility,admission,owner_membership_active,created_at from ranking) t order by 1", String.class));
    return rows;
  }

  private List<String> catalog() {
    var result = new ArrayList<String>();
    for (String sql : List.of(
        "select 'column:'||table_name||':'||column_name||':'||data_type||':'||udt_name||':'||coalesce(character_maximum_length::text,'')||':'||is_nullable||':'||coalesce(column_default,'')||':'||is_identity||':'||coalesce(identity_generation,'') from information_schema.columns where table_schema=? and table_name<>'flyway_schema_history' order by table_name,ordinal_position",
        "select 'constraint:'||c.relname||':'||con.conname||':'||pg_get_constraintdef(con.oid) from pg_constraint con join pg_class c on c.oid=con.conrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname=? and c.relname<>'flyway_schema_history' order by c.relname,con.conname",
        "select 'index:'||tablename||':'||indexname||':'||indexdef from pg_indexes where schemaname=? and tablename<>'flyway_schema_history' order by tablename,indexname")) {
      result.addAll(jdbc.queryForList(sql, String.class, schema));
    }
    return result;
  }
}
