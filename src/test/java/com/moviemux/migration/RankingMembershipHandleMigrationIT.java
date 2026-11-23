package com.moviemux.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moviemux.ranking.adapter.repository.postgres.OfflineLegacyRankingMigration;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
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

class RankingMembershipHandleMigrationIT {
  private String url, user, password, schema;
  private DriverManagerDataSource source;
  private JdbcTemplate jdbc;

  @BeforeEach void createDisposableSchema() throws SQLException {
    url = System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    String name = "membership_handle_test_" + UUID.randomUUID().toString().replace("-", "");
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

  @Test void v15ToV16BackfillsOpaqueDistinctHandlesAndPreservesLegacyRowsConstraintsAndReplay() {
    assertThat(flyway("15").migrate().migrationsExecuted).isEqualTo(15);
    UUID owner = account(), member = account(), rank = UUID.randomUUID();
    var offline = new OfflineLegacyRankingMigration(source, 100);
    var originalPlan = offline.preview(owner, rank);
    assertThat(offline.apply(originalPlan, true)).isEqualTo(new OfflineLegacyRankingMigration.Result(false, 2, 0, 0));

    UUID secondRank = ranking(owner);
    addMember(secondRank, member, false);
    var membershipRows = oldMembershipRows("to_jsonb(m)");
    var rankingRows = rows("ranking");
    var grants = rows("ranking_membership_grant");
    var authors = rows("ranking_author_identity");
    var ledger = rows("legacy_ranking_migration");
    var oldConstraints = membershipConstraints();

    var v16 = flyway("16");
    assertThat(v16.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(oldMembershipRows("to_jsonb(m)-'membership_id'")).isEqualTo(membershipRows);
    assertThat(rows("ranking")).isEqualTo(rankingRows);
    assertThat(rows("ranking_membership_grant")).isEqualTo(grants);
    assertThat(rows("ranking_author_identity")).isEqualTo(authors);
    assertThat(membershipConstraints()).containsAll(oldConstraints).hasSize(oldConstraints.size() + 1)
        .anyMatch(value -> value.startsWith("ranking_membership_membership_id_uk:UNIQUE (membership_id)"));
    var handles = jdbc.queryForList("select membership_id from ranking_membership order by membership_id", UUID.class);
    assertThat(handles).hasSize(4).doesNotHaveDuplicates().doesNotContainNull().doesNotContain(owner, member);
    assertThat(handle(rank, member)).isNotEqualTo(handle(secondRank, member));

    assertThat(jdbc.queryForMap("""
        select data_type,is_nullable,column_default from information_schema.columns
        where table_schema=? and table_name='ranking_membership' and column_name='membership_id'
        """, schema)).containsEntry("data_type", "uuid").containsEntry("is_nullable", "NO")
        .containsEntry("column_default", "gen_random_uuid()");
    assertThat(jdbc.queryForObject("""
        select pg_get_indexdef(indexrelid) from pg_index
        where indexrelid='ranking_membership_active_membership_idx'::regclass
        """, String.class)).endsWith("USING btree (ranking_id, membership_id) WHERE active");

    assertThat(v16.migrate().migrationsExecuted).isZero();
    assertThat(jdbc.queryForList("select membership_id from ranking_membership order by membership_id", UUID.class))
        .isEqualTo(handles);
    assertThat(offline.preview(owner, rank)).isEqualTo(originalPlan);
    assertThat(offline.apply(originalPlan, true)).isEqualTo(new OfflineLegacyRankingMigration.Result(true, 2, 0, 0));
    assertThat(rows("legacy_ranking_migration")).isEqualTo(ledger);

    // The same legacy column still contributes to algorithm 1 after excluding just the handle.
    jdbc.update("update ranking_membership set active=false where ranking_id=? and user_id=?", rank, member);
    assertThatThrownBy(() -> offline.apply(originalPlan, true)).hasMessage("migration.already_applied_different_state");
  }

  @Test void existingWriterGetsDefaultAndHandleSurvivesOrdinaryMembershipUpdates() {
    assertThat(flyway("16").migrate().migrationsExecuted).isEqualTo(16);
    UUID owner = account(), member = account(), other = account();
    UUID rank = ranking(owner), secondRank = ranking(owner);
    addMember(rank, member, true);
    addMember(secondRank, member, true);
    UUID handle = handle(rank, member);
    assertThat(handle).isNotNull().isNotIn(owner, member, handle(secondRank, member));
    expectViolation("23505", "ranking_membership_membership_id_uk", () ->
        jdbc.update("""
            insert into ranking_membership(ranking_id,user_id,active,created_at,membership_id)
            values (?,?,true,now(),?)
            """, secondRank, other, handle));
    expectViolation("23502", null, () ->
        jdbc.update("""
            insert into ranking_membership(ranking_id,user_id,active,created_at,membership_id)
            values (?,?,true,now(),null)
            """, secondRank, other));

    jdbc.update("update ranking_membership set active=false where ranking_id=? and user_id=?", rank, member);
    assertThat(handle(rank, member)).isEqualTo(handle);
    assertThat(jdbc.queryForList("""
        select membership_id from ranking_membership where ranking_id=? and active and membership_id>? order by membership_id
        """, UUID.class, rank, new UUID(0, 0))).containsExactly(handle(rank, owner));
  }

  private UUID handle(UUID rank, UUID account) {
    return jdbc.queryForObject("select membership_id from ranking_membership where ranking_id=? and user_id=?",
        UUID.class, rank, account);
  }

  private UUID ranking(UUID owner) {
    UUID id = UUID.randomUUID();
    new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
      jdbc.update("""
          insert into ranking(id,owner_user_id,visibility,admission,created_at) values (?,?,'PRIVATE','INVITATION',now())
          """, id, owner);
      addMember(id, owner, true);
    });
    return id;
  }

  private void addMember(UUID rank, UUID account, boolean active) {
    jdbc.update("insert into ranking_membership(ranking_id,user_id,active,created_at) values (?,?,?,now())",
        rank, account, active);
  }

  private UUID account() {
    UUID account = UUID.randomUUID();
    jdbc.update("""
        insert into "user"(id,created_at,email,name,password) values (?,now(),?,'Synthetic','synthetic')
        """, account, account + "@example.invalid");
    return account;
  }

  private List<String> oldMembershipRows(String projection) {
    return jdbc.queryForList("select (" + projection + ")::text from ranking_membership m order by ranking_id,user_id",
        String.class);
  }

  private List<String> rows(String table) {
    return jdbc.queryForList("select to_jsonb(t)::text from " + table + " t order by 1", String.class);
  }

  private List<String> membershipConstraints() {
    return jdbc.queryForList("""
        select conname||':'||pg_get_constraintdef(oid) from pg_constraint
        where conrelid='ranking_membership'::regclass or confrelid='ranking_membership'::regclass
        order by 1
        """, String.class);
  }

  private Flyway flyway(String target) {
    return Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
        .createSchemas(false).baselineOnMigrate(false).cleanDisabled(true).target(target).load();
  }

  private void expectViolation(String sqlState, String constraint, Runnable statement) {
    assertThatThrownBy(statement::run).hasRootCauseInstanceOf(PSQLException.class)
        .satisfies(error -> {
          var postgres = (PSQLException) error.getCause();
          assertThat(postgres.getSQLState()).isEqualTo(sqlState);
          if (constraint != null) assertThat(postgres.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
        });
  }
}
