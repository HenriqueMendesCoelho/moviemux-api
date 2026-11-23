package com.moviemux.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moviemux.ranking.adapter.repository.postgres.OfflineLegacyRankingMigration;
import com.moviemux.rankingcontent.adapter.repository.postgres.EntryExpiryWorker;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class RatingCopyReceiptMigrationIT {
  private static final String RECEIPT = "ranking_rating_copy_receipt";
  private String url, user, password, schema;
  private DriverManagerDataSource source;
  private JdbcTemplate jdbc;

  @BeforeEach void createDisposableSchema() throws SQLException {
    url = System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    String name = "rating_copy_migration_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, user, password);
         var statement = connection.createStatement()) {
      statement.execute("create schema " + name);
      schema = name;
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

  @AfterEach void removeOwnedSchema() throws SQLException {
    if (schema != null) try (var connection = DriverManager.getConnection(url, user, password);
                            var statement = connection.createStatement()) {
      statement.execute("drop schema " + schema + " cascade");
    }
  }

  @Test void v14ToV15AddsOnlyEmptyReceiptStorageAndPreservesAlgorithmOneReplay() {
    assertThat(flyway("14").migrate().migrationsExecuted).isEqualTo(14);
    UUID owner = account(), rank = UUID.randomUUID(), movie = movie(owner);
    jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),null)", movie, owner);
    var offline = new OfflineLegacyRankingMigration(source, 100);
    var originalPlan = offline.preview(owner, rank);
    assertThat(offline.apply(originalPlan, true)).isEqualTo(new OfflineLegacyRankingMigration.Result(false, 1, 1, 1));
    var before = applicationRows();
    int foreignKeys = foreignKeyCount();

    var v15 = flyway("15");
    assertThat(v15.migrate().migrationsExecuted).isEqualTo(1);
    var after = applicationRows();
    assertThat(after).hasSize(before.size() + 1).containsKey(RECEIPT);
    assertThat(after.remove(RECEIPT)).isEmpty();
    assertThat(after).isEqualTo(before);
    assertThat(foreignKeyCount()).isEqualTo(foreignKeys + 1);
    assertThat(jdbc.queryForList("""
        select column_name||':'||udt_name||':'||is_nullable from information_schema.columns
        where table_schema=? and table_name=? order by ordinal_position
        """, String.class, schema, RECEIPT)).containsExactly(
            "operation_id:uuid:NO", "ranking_id:uuid:NO", "round_id:uuid:NO", "author_id:uuid:NO", "expires_at:timestamptz:NO");
    assertThat(jdbc.queryForObject("""
        select pg_get_indexdef(indexrelid) from pg_index
        where indexrelid='ranking_rating_copy_receipt_expiry_idx'::regclass
        """, String.class)).endsWith("USING btree (expires_at, operation_id)");
    assertThat(v15.migrate().migrationsExecuted).isZero();
    assertThat(offline.preview(owner, rank)).isEqualTo(originalPlan);
    assertThat(offline.apply(originalPlan, true)).isEqualTo(new OfflineLegacyRankingMigration.Result(true, 1, 1, 1));
    assertThat(applicationRows().get("legacy_ranking_migration")).isEqualTo(before.get("legacy_ranking_migration"));
  }

  @Test void receiptsEnforceDestinationScopeAndCascadeThroughExistingEntryPurgeOnly() {
    flyway("15").migrate();
    var first = destination(account());
    var second = destination(account());
    UUID firstOperation = UUID.randomUUID(), secondOperation = UUID.randomUUID();

    expectConstraint("23514", "ranking_rating_copy_receipt_expiry_finite_ck",
        () -> insert(firstOperation, first, "infinity"));
    expectConstraint("23514", "ranking_rating_copy_receipt_expiry_finite_ck",
        () -> insert(firstOperation, first, "-infinity"));
    expectConstraint("23503", "ranking_rating_copy_receipt_destination_fk",
        () -> insert(firstOperation, new Destination(second.rank(), second.entry(), second.round(), first.author()),
            "2050-01-01 00:00:00+00"));
    expectConstraint("23503", "ranking_rating_copy_receipt_destination_fk",
        () -> insert(firstOperation, new Destination(first.rank(), first.entry(), second.round(), first.author()),
            "2050-01-01 00:00:00+00"));

    assertThat(insert(firstOperation, first, "2050-01-01 00:00:00+00")).isEqualTo(1);
    expectConstraint("23505", "ranking_rating_copy_receipt_pkey",
        () -> insert(firstOperation, second, "2050-01-01 00:00:00+00"));
    expectConstraint("23505", "ranking_rating_copy_receipt_destination_uk",
        () -> insert(UUID.randomUUID(), first, "2050-01-01 00:00:00+00"));
    assertThat(insert(secondOperation, second, "2000-01-01 00:00:00+00")).isEqualTo(1);

    assertThat(jdbc.update("""
        update ranking_movie_entry set removed_at=clock_timestamp(),removal_token=?,lifecycle_version=1
        where ranking_id=? and entry_id=?
        """, UUID.randomUUID(), first.rank(), first.entry())).isEqualTo(1);
    var expiry = new EntryExpiryWorker(source);
    assertThat(expiry.runBatch(5)).isZero();
    assertThat(operations()).containsExactlyInAnyOrder(firstOperation, secondOperation);

    assertThat(jdbc.update("""
        update ranking_movie_entry set removed_at=clock_timestamp()-interval '25 hours'
        where ranking_id=? and entry_id=?
        """, first.rank(), first.entry())).isEqualTo(1);
    assertThat(expiry.runBatch(5)).isEqualTo(1);
    assertThat(operations()).containsExactly(secondOperation);
    assertThat(jdbc.queryForList("select ranking_id from ranking_movie_rating", UUID.class))
        .containsExactly(second.rank());
    assertThat(jdbc.queryForList("select id from ranking", UUID.class))
        .containsExactlyInAnyOrder(first.rank(), second.rank());
  }

  private Destination destination(UUID owner) {
    UUID rank = UUID.randomUUID(), entry = UUID.randomUUID(), round = UUID.randomUUID(), author = UUID.randomUUID();
    UUID movie = movie(owner);
    new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
      jdbc.update("""
          insert into ranking(id,owner_user_id,visibility,admission,created_at) values (?,?,'PRIVATE','INVITATION',now())
          """, rank, owner);
      jdbc.update("insert into ranking_membership(ranking_id,user_id,active,created_at) values (?,?,true,now())",
          rank, owner);
      jdbc.update("""
          insert into ranking_author_identity(ranking_id,author_id,account_id,deleted_public_code) values (?,?,?,?)
          """, rank, author, owner, UUID.randomUUID());
      jdbc.update("insert into ranking_movie_entry(ranking_id,entry_id,movie_id,created_at) values (?,?,?,now())",
          rank, entry, movie);
      jdbc.update("""
          insert into ranking_movie_round(ranking_id,entry_id,round_id,round_number,created_at,revealed)
          values (?,?,?,1,now(),false)
          """, rank, entry, round);
      jdbc.update("insert into ranking_movie_rating(ranking_id,round_id,author_id,score,created_at) values (?,?,?,8,now())",
          rank, round, author);
    });
    return new Destination(rank, entry, round, author);
  }

  private UUID account() {
    UUID account = UUID.randomUUID();
    jdbc.update("""
        insert into "user"(id,created_at,email,name,password) values (?,now(),?,'Synthetic','synthetic')
        """, account, account + "@example.invalid");
    return account;
  }

  private UUID movie(UUID owner) {
    UUID movie = UUID.randomUUID();
    jdbc.update("""
        insert into movie(id,created_at,description,director,english_title,portuguese_title,user_id)
        values (?,now(),'Synthetic','Synthetic','Synthetic','Synthetic',?)
        """, movie, owner);
    return movie;
  }

  private int insert(UUID operation, Destination destination, String expiresAt) {
    return jdbc.update("""
        insert into ranking_rating_copy_receipt(operation_id,ranking_id,round_id,author_id,expires_at)
        values (?,?,?,?,cast(? as timestamptz))
        """, operation, destination.rank(), destination.round(), destination.author(), expiresAt);
  }

  private List<UUID> operations() {
    return jdbc.queryForList("select operation_id from ranking_rating_copy_receipt", UUID.class);
  }

  private Map<String, List<String>> applicationRows() {
    var tables = jdbc.queryForList("""
        select table_name from information_schema.tables where table_schema=? and table_type='BASE TABLE'
        and table_name <> 'flyway_schema_history' order by table_name
        """, String.class, schema);
    var result = new LinkedHashMap<String, List<String>>();
    for (String table : tables) {
      // Names come only from this isolated schema's catalog and are quoted as identifiers.
      result.put(table, jdbc.queryForList("select to_jsonb(r)::text from \"" + table.replace("\"", "\"\"")
          + "\" r order by 1", String.class));
    }
    return result;
  }

  private int foreignKeyCount() {
    return jdbc.queryForObject("""
        select count(*) from pg_constraint c join pg_namespace n on n.oid=c.connamespace
        where n.nspname=? and c.contype='f'
        """, Integer.class, schema);
  }

  private Flyway flyway(String target) {
    return Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
        .createSchemas(false).baselineOnMigrate(false).cleanDisabled(true).target(target).load();
  }

  private void expectConstraint(String sqlState, String constraint, Runnable statement) {
    assertThatThrownBy(statement::run).hasRootCauseInstanceOf(PSQLException.class)
        .satisfies(error -> {
          var postgres = (PSQLException) error.getCause();
          assertThat(postgres.getSQLState()).isEqualTo(sqlState);
          assertThat(postgres.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
        });
  }

  private record Destination(UUID rank, UUID entry, UUID round, UUID author) {}
}
