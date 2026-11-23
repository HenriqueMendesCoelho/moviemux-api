package com.moviemux.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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

class RankingInvitationMigrationIT {
  private String url, user, password, schema;
  private JdbcTemplate jdbc;
  private TransactionTemplate transactions;

  @BeforeEach void createDisposableSchema() throws SQLException {
    url = System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    schema = "invitation_migration_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, user, password);
         var statement = connection.createStatement()) {
      statement.execute("create schema " + schema);
    }
    var source = new DriverManagerDataSource(url, user, password) {
      @Override public Connection getConnection() throws SQLException {
        var connection = super.getConnection();
        connection.setSchema(schema);
        try (var statement = connection.createStatement()) {
          statement.execute("set timezone='America/New_York'");
        }
        return connection;
      }
    };
    jdbc = new JdbcTemplate(source);
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
  }

  @AfterEach void removeOwnedSchema() throws SQLException {
    if (schema != null) try (var connection = DriverManager.getConnection(url, user, password);
                            var statement = connection.createStatement()) {
      statement.execute("drop schema " + schema + " cascade");
    }
  }

  @Test void v9UpgradePreservesRowsAndEnforcesInvitationConstraintsWithoutBackfill() {
    assertThat(flyway("9").migrate().migrationsExecuted).isEqualTo(9);
    UUID rank = UUID.randomUUID(), owner = account(), invitee = account();
    transactions.executeWithoutResult(status -> {
      jdbc.update("insert into ranking(id,owner_user_id,visibility,admission,created_at,name) "
          + "values (?,?,'PRIVATE','INVITATION',now(),'Synthetic')", rank, owner);
      jdbc.update("insert into ranking_membership(ranking_id,user_id,active,created_at) values (?,?,true,now())",
          rank, owner);
    });
    var beforeRank = rows("ranking");
    var beforeMembership = rows("ranking_membership");
    var beforeAccounts = rows("\"user\"");

    var v10 = flyway("10");
    assertThat(v10.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(rows("ranking_invitation")).isEmpty();
    assertThat(rows("ranking")).isEqualTo(beforeRank);
    assertThat(rows("ranking_membership")).isEqualTo(beforeMembership);
    assertThat(rows("\"user\"")).isEqualTo(beforeAccounts);
    assertThat(v10.migrate().migrationsExecuted).isZero();

    // Cross the spring-forward boundary: 168 elapsed hours are not seven calendar days.
    var issued = OffsetDateTime.parse("2026-03-06T12:00:00-05:00");
    var exactExpiry = OffsetDateTime.parse("2026-03-13T13:00:00-04:00");
    var calendarExpiry = OffsetDateTime.parse("2026-03-13T12:00:00-04:00");
    UUID first = UUID.randomUUID(), replacement = UUID.randomUUID();
    expectViolation("23514", "ranking_invitation_expiry_ck",
        () -> insert(first, rank, owner, invitee, issued, calendarExpiry));
    assertThat(insert(first, rank, owner, invitee, issued, exactExpiry)).isEqualTo(1);
    expectViolation("23505", "ranking_invitation_pending_unique_idx",
        () -> insert(replacement, rank, owner, invitee, issued, exactExpiry));
    expectViolation("23514", "ranking_invitation_terminal_state_ck", () ->
        jdbc.update("update ranking_invitation set consumed_at=issued_at, revoked_at=issued_at where invite_id=?", first));
    expectViolation("23503", "ranking_invitation_invitee_user_id_fkey",
        () -> jdbc.update("delete from \"user\" where id=?", invitee));
    assertThat(jdbc.update("update ranking_invitation set revoked_at=expires_at where invite_id=?", first)).isEqualTo(1);
    assertThat(insert(replacement, rank, owner, invitee, issued, exactExpiry)).isEqualTo(1);
    assertThat(rows("ranking_invitation")).hasSize(2);
    assertThat(rows("ranking_membership")).isEqualTo(beforeMembership);
  }

  private Flyway flyway(String target) {
    return Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
        .createSchemas(false).baselineOnMigrate(false).cleanDisabled(true).target(target).load();
  }

  private UUID account() {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into "user"(id,created_at,email,name,password) values (?,now(),?,'Synthetic','synthetic')
        """, id, id + "@example.invalid");
    return id;
  }

  private int insert(UUID id, UUID rank, UUID from, UUID to, OffsetDateTime issued, OffsetDateTime expires) {
    return jdbc.update("""
        insert into ranking_invitation(invite_id,ranking_id,invited_by_user_id,invitee_user_id,issued_at,expires_at)
        values (?,?,?,?,?,?)
        """, id, rank, from, to, issued, expires);
  }

  private List<String> rows(String table) {
    return jdbc.queryForList("select to_jsonb(r)::text from " + table + " r order by 1", String.class);
  }

  private void expectViolation(String sqlState, String constraint, Runnable statement) {
    assertThatThrownBy(statement::run).hasRootCauseInstanceOf(PSQLException.class)
        .satisfies(error -> {
          var postgres = (PSQLException) error.getCause();
          assertThat(postgres.getSQLState()).isEqualTo(sqlState);
          assertThat(postgres.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
        });
  }
}
