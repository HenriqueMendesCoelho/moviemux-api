package com.moviemux.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** V19 is additive, conservatively initialized, and restart-safe against an actual V18 schema. */
class RegistrationModeMigrationIT {
  private String url;
  private String user;
  private String password;
  private String schema;
  private JdbcTemplate jdbc;

  @BeforeEach
  void createDisposableSchema() throws SQLException {
    url = System.getenv("TEST_DATABASE_URL");
    assertThat(url).as("Run migration tests only against a disposable loopback PostgreSQL database")
        .matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    String owned = "registration_migration_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var connection = DriverManager.getConnection(url, user, password);
         var statement = connection.createStatement()) {
      statement.execute("create schema " + owned);
      schema = owned;
    }
    jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password) {
      @Override public Connection getConnection() throws SQLException {
        var connection = super.getConnection();
        connection.setSchema(schema);
        return connection;
      }
    });
  }

  @AfterEach
  void removeOnlyOwnedSchema() throws SQLException {
    if (schema != null) {
      try (var connection = DriverManager.getConnection(url, user, password);
           var statement = connection.createStatement()) {
        statement.execute("drop schema " + schema + " cascade");
      }
    }
  }

  @Test
  void populatedV18UpgradePreservesLegacyAccountsProfileInvitesAndHistory() {
    assertThat(flyway("18").migrate().migrationsExecuted).isEqualTo(18);
    UUID admin = UUID.randomUUID();
    UUID member = UUID.randomUUID();
    jdbc.update("""
        insert into "user"(id,created_at,email,name,password,updated_at,redefine_password_key,
          redefine_password_key_created_at)
        values (?,timestamptz '2025-02-01 12:30:00+00','fixture-admin@example.invalid',
          'Synthetic admin','synthetic-password-hash',timestamptz '2025-03-01 12:00:00+00',
          'synthetic-reset-marker',timestamptz '2025-03-01 12:00:00+00')
        """, admin);
    jdbc.update("""
        insert into "user"(id,created_at,email,name,password)
        values (?,timestamptz '2025-02-02 12:30:00+00','fixture-member@example.invalid',
          'Synthetic member','synthetic-password-hash-member')
        """, member);
    jdbc.update("insert into user_roles(user_id,roles) values (?,1),(?,2),(?,2)",admin,admin,member);
    jdbc.update("insert into user_preferences(user_id,notify) values (?,false),(?,true)",admin,member);
    jdbc.update("""
        insert into user_statistics(user_id,consecutive_failed_login_attempts,last_login_at)
        values (?,2,timestamptz '2025-03-01 12:00:00+00'),(?,0,null)
        """,admin,member);
    jdbc.update("insert into user_invites(code) values ('KB-12345678'),('KB-987654321')");

    var before = snapshots();
    assertThat(flyway("19").migrate().migrationsExecuted).isEqualTo(1);
    assertThat(flyway("19").info().current().getVersion().getVersion()).isEqualTo("19");
    assertThat(policy()).containsEntry("singleton",true)
        .containsEntry("mode","INVITE_ONLY").containsEntry("bootstrap_completed",true);
    assertThat(rows("registration_waitlist")).isEmpty();
    assertThat(snapshots()).isEqualTo(before);
    assertThat(jdbc.queryForObject(
        "select count(*) from flyway_schema_history where version='19' and success",Long.class))
        .isEqualTo(1);

    // Simulate an authorized policy change after successful upgrade; migration restart preserves it.
    jdbc.update("update instance_registration_policy set mode='OPEN' where singleton=true");
    var changed = policy();
    assertThat(flyway("19").migrate().migrationsExecuted).isZero();
    assertThat(policy()).isEqualTo(changed);
    assertThat(snapshots()).isEqualTo(before);
  }

  @Test
  void emptyV18UpgradeKeepsPrivateBootstrapAvailableAndRestartDoesNotReinitialize() {
    assertThat(flyway("18").migrate().migrationsExecuted).isEqualTo(18);
    assertThat(rows("\"user\"")).isEmpty();
    assertThat(rows("user_invites")).isEmpty();
    assertThat(flyway("19").migrate().migrationsExecuted).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from instance_registration_policy",Long.class)).isEqualTo(1);
    assertThat(policy()).containsEntry("singleton",true)
        .containsEntry("mode","INVITE_ONLY").containsEntry("bootstrap_completed",false);
    assertThat(rows("registration_waitlist")).isEmpty();
    var unchanged=policy();
    assertThat(flyway("19").migrate().migrationsExecuted).isZero();
    assertThat(policy()).isEqualTo(unchanged);
  }

  private Map<String,Object> policy() {
    return jdbc.queryForMap("select singleton,mode,bootstrap_completed from instance_registration_policy");
  }

  private Flyway flyway(String target) {
    return Flyway.configure().dataSource(url,user,password)
        .schemas(schema).defaultSchema(schema).createSchemas(false).baselineOnMigrate(false)
        .cleanDisabled(true).target(target).locations("classpath:db/migration").load();
  }

  private List<String> rows(String table) {
    return jdbc.queryForList("select to_jsonb(t)::text from "+table+" t order by to_jsonb(t)::text",String.class);
  }

  private Map<String,List<String>> snapshots() {
    return Map.of("account",rows("\"user\""),"roles",rows("user_roles"),
        "preferences",rows("user_preferences"),"statistics",rows("user_statistics"),
        "invites",rows("user_invites"),"history",jdbc.queryForList("""
            select to_jsonb(h)::text from flyway_schema_history h
            where installed_rank <= 18 order by installed_rank
            """,String.class));
  }
}
