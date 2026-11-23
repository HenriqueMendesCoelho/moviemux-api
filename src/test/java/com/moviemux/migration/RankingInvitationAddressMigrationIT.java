package com.moviemux.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class RankingInvitationAddressMigrationIT {
  private String url, user, password, schema;
  private JdbcTemplate jdbc;
  private TransactionTemplate transactions;

  @BeforeEach void createDisposableSchema() throws SQLException {
    url = System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    schema = "inv_addr_migration_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, user, password);
         var statement = connection.createStatement()) {
      statement.execute("create schema " + schema);
    }
    var source = new DriverManagerDataSource(url, user, password) {
      @Override public Connection getConnection() throws SQLException {
        var connection = super.getConnection();
        connection.setSchema(schema);
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

  @Test void v10UpgradePreservesInvitationsAndNeverAutomaticallyIssuesAddresses() {
    assertThat(flyway("10").migrate().migrationsExecuted).isEqualTo(10);
    UUID rank = UUID.randomUUID(), owner = account(), invitee = account();
    transactions.executeWithoutResult(status -> {
      jdbc.update("insert into ranking(id,owner_user_id,visibility,admission,created_at,name) "
          + "values (?,?,'PRIVATE','INVITATION',now(),'Synthetic')", rank, owner);
      jdbc.update("insert into ranking_membership(ranking_id,user_id,active,created_at) values (?,?,true,now())",
          rank, owner);
      jdbc.update("""
          insert into ranking_invitation(invite_id,ranking_id,invited_by_user_id,invitee_user_id,issued_at,expires_at)
          values (?,?,?,?,now(),now()+interval '168 hours')
          """, UUID.randomUUID(), rank, owner, invitee);
    });
    var beforeRank = rows("ranking");
    var beforeMembership = rows("ranking_membership");
    var beforeInvites = rows("ranking_invitation");
    var beforeAccounts = rows("\"user\"");

    var v11 = flyway("11");
    assertThat(v11.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(v11.info().current().getVersion().getVersion()).isEqualTo("11");
    assertThat(rows("ranking_invitation_address")).isEmpty();
    assertThat(rows("ranking")).isEqualTo(beforeRank);
    assertThat(rows("ranking_membership")).isEqualTo(beforeMembership);
    assertThat(rows("ranking_invitation")).isEqualTo(beforeInvites);
    assertThat(rows("\"user\"")).isEqualTo(beforeAccounts);
    account();
    assertThat(rows("ranking_invitation_address")).isEmpty();
    assertThat(v11.migrate().migrationsExecuted).isZero();
    assertThat(rows("ranking_invitation_address")).isEmpty();
  }

  @Test void addressStoresOnlyFixedLengthProtectedBytesAndRestrictiveAccountForeignKey() {
    flyway("11").migrate();
    assertThat(jdbc.queryForList("""
        select column_name || ':' || data_type || ':' || is_nullable
        from information_schema.columns
        where table_schema=? and table_name='ranking_invitation_address'
        order by ordinal_position
        """, String.class, schema)).containsExactly(
            "account_id:uuid:NO", "lookup_digest:bytea:NO", "encrypted_code:bytea:NO");

    UUID first = account(), second = account(), third = account();
    byte[] firstDigest = new byte[32], secondDigest = new byte[32], thirdDigest = new byte[32];
    secondDigest[31] = (byte) 0x80;
    thirdDigest[31] = (byte) 0xff;
    byte[] envelope = new byte[50];
    assertThat(insert(first, firstDigest, envelope)).isEqualTo(1);
    assertThat(insert(second, secondDigest, envelope)).isEqualTo(1);
    assertThat(findAccount(firstDigest)).isEqualTo(first);
    assertThat(findAccount(secondDigest)).isEqualTo(second);
    expectViolation("23505", "ranking_invitation_address_lookup_digest_key",
        () -> insert(third, firstDigest, envelope));
    expectViolation("23505", "ranking_invitation_address_pkey",
        () -> insert(first, thirdDigest, envelope));

    for (int length : List.of(0, 31, 33)) {
      expectViolation("23514", "ranking_invitation_address_lookup_digest_check",
          () -> insert(third, new byte[length], envelope));
    }
    for (int length : List.of(0, 49, 51)) {
      expectViolation("23514", "ranking_invitation_address_encrypted_code_check",
          () -> insert(third, thirdDigest, new byte[length]));
    }
    expectViolation("23503", "ranking_invitation_address_account_id_fkey",
        () -> insert(UUID.randomUUID(), thirdDigest, envelope));
    assertThat(insert(third, thirdDigest, envelope)).isEqualTo(1);
    expectViolation("23503", "ranking_invitation_address_account_id_fkey",
        () -> jdbc.update("delete from \"user\" where id=?", third));
    assertThat(findAccount(thirdDigest)).isEqualTo(third);
    assertThat(rows("ranking_invitation_address")).hasSize(3);
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

  private int insert(UUID id, byte[] digest, byte[] envelope) {
    return jdbc.update("""
        insert into ranking_invitation_address(account_id,lookup_digest,encrypted_code) values (?,?,?)
        """, id, digest, envelope);
  }

  private UUID findAccount(byte[] digest) {
    return jdbc.queryForObject(
        "select account_id from ranking_invitation_address where lookup_digest=?", UUID.class, digest);
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
