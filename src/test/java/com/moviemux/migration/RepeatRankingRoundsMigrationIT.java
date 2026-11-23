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

class RepeatRankingRoundsMigrationIT {
  private String url, user, password, schema;
  private DriverManagerDataSource source;
  private JdbcTemplate jdbc;
  private TransactionTemplate transactions;

  @BeforeEach void createDisposableSchema() throws SQLException {
    url = System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    schema = "round_creation_migration_test_" + UUID.randomUUID().toString().replace("-", "");
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
    transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
  }

  @AfterEach void removeOwnedSchema() throws SQLException {
    if (schema != null) try (var connection = DriverManager.getConnection(url, user, password);
                            var statement = connection.createStatement()) {
      statement.execute("drop schema " + schema + " cascade");
    }
  }

  @Test void v13ToV14LeavesLegacyHistoryExactAndReplayStillWorksUntilDestinationChanges() {
    assertThat(flyway("13").migrate().migrationsExecuted).isEqualTo(13);
    UUID owner = account(), rank = UUID.randomUUID(), movie = movie(owner);
    jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),null)", movie, owner);

    var offline = new OfflineLegacyRankingMigration(source, 100);
    var originalPlan = offline.preview(owner, rank);
    assertThat(offline.apply(originalPlan, true)).isEqualTo(new OfflineLegacyRankingMigration.Result(false, 1, 1, 1));
    var oldRank = rows("ranking", "repeat_rounds_enabled");
    var oldRounds = oldRoundRows();
    var oldRatings = rows("ranking_movie_rating");
    var oldLedger = rows("legacy_ranking_migration");
    var oldMap = rows("legacy_ranking_movie_map");
    var oldRoundForeignKeys = roundForeignKeys();

    var v14 = flyway("14");
    assertThat(v14.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(rows("ranking", "repeat_rounds_enabled")).isEqualTo(oldRank);
    assertThat(oldRoundRows()).isEqualTo(oldRounds);
    assertThat(rows("ranking_movie_rating")).isEqualTo(oldRatings);
    assertThat(rows("legacy_ranking_migration")).isEqualTo(oldLedger);
    assertThat(rows("legacy_ranking_movie_map")).isEqualTo(oldMap);
    assertThat(roundForeignKeys()).containsAll(oldRoundForeignKeys)
        .hasSize(oldRoundForeignKeys.size() + 1).anyMatch(
            fk -> fk.startsWith("ranking_movie_round_creation_author_fk:")
                && fk.contains("FOREIGN KEY (ranking_id, creation_author_id)")
                && fk.contains("ranking_author_identity(ranking_id, author_id)"));
    assertThat(jdbc.queryForObject("select repeat_rounds_enabled from ranking where id=?", Boolean.class, rank)).isFalse();
    assertThat(jdbc.queryForMap("""
        select creation_command_id,creation_author_id,creation_input_fingerprint from ranking_movie_round
        """)).containsEntry("creation_command_id", null).containsEntry("creation_author_id", null)
        .containsEntry("creation_input_fingerprint", null);
    assertThat(v14.migrate().migrationsExecuted).isZero();
    assertThat(offline.preview(owner, rank)).isEqualTo(originalPlan);
    assertThat(offline.apply(originalPlan, true)).isEqualTo(new OfflineLegacyRankingMigration.Result(true, 1, 1, 1));

    jdbc.update("update ranking set repeat_rounds_enabled=true where id=?", rank);
    expectChangedReplay(offline, originalPlan);
    jdbc.update("update ranking set repeat_rounds_enabled=false where id=?", rank);
    assertThat(offline.apply(originalPlan, true).alreadyApplied()).isTrue();
    UUID entry = jdbc.queryForObject("select entry_id from ranking_movie_entry", UUID.class);
    UUID author = author(rank, owner);
    assertThat(insertRound(rank, entry, 2, UUID.randomUUID(), author, new byte[32])).isEqualTo(1);
    expectChangedReplay(offline, originalPlan);
    assertThat(rows("legacy_ranking_migration")).isEqualTo(oldLedger);
    assertThat(rows("legacy_ranking_movie_map")).isEqualTo(oldMap);
  }

  @Test void commandsRequireAllMetadataAndAContextualAuthorWithUniquenessOnlyWithinAnEntry() {
    flyway("14").migrate();
    UUID first = account(), second = account(), rank = rank(first), otherRank = rank(second);
    transactions.executeWithoutResult(status -> addMember(rank, second));
    UUID firstAuthor = identify(rank, first), secondAuthor = identify(rank, second), foreignAuthor = identify(otherRank, second);
    UUID entry = UUID.randomUUID(), secondEntry = UUID.randomUUID(), command = UUID.randomUUID();
    UUID movie = movie(first), anotherMovie = movie(second);
    insertEntry(rank, entry, movie);
    insertEntry(rank, secondEntry, anotherMovie);
    insertEntry(otherRank, entry, movie);
    assertThat(jdbc.queryForList("select repeat_rounds_enabled from ranking", Boolean.class)).containsExactly(false, false);

    assertThat(insertRound(rank, entry, 1, null, null, null)).isEqualTo(1);
    for (int i = 1; i < 7; i++) {
      final int bits = i;
      expectConstraint("23514", "ranking_movie_round_creation_metadata_ck", () ->
          insertRound(rank, entry, 2, (bits & 1) == 0 ? null : command,
              (bits & 2) == 0 ? null : firstAuthor, (bits & 4) == 0 ? null : new byte[32]));
    }
    for (int length : List.of(0, 31, 33)) {
      expectConstraint("23514", "ranking_movie_round_creation_metadata_ck", () ->
          insertRound(rank, entry, 2, command, firstAuthor, new byte[length]));
    }
    expectConstraint("23514", "ranking_movie_round_creation_metadata_ck", () ->
        insertRound(rank, secondEntry, 1, command, firstAuthor, new byte[32]));
    expectConstraint("23503", "ranking_movie_round_creation_author_fk", () ->
        insertRound(rank, entry, 2, command, foreignAuthor, new byte[32]));
    assertThat(insertRound(rank, entry, 2, command, firstAuthor, new byte[32])).isEqualTo(1);
    expectConstraint("23505", "ranking_movie_round_creation_command_uk", () ->
        insertRound(rank, entry, 3, command, secondAuthor, new byte[32]));
    assertThat(insertRound(rank, secondEntry, 2, command, secondAuthor, new byte[32])).isEqualTo(1);
    assertThat(insertRound(otherRank, entry, 2, command, foreignAuthor, new byte[32])).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from ranking_movie_round", Integer.class)).isEqualTo(4);
  }

  private void expectChangedReplay(OfflineLegacyRankingMigration offline, OfflineLegacyRankingMigration.Plan plan) {
    assertThatThrownBy(() -> offline.preview(plan.ownerId(), plan.rankingId()))
        .hasMessage("migration.already_applied_different_state");
    assertThatThrownBy(() -> offline.apply(plan, true))
        .hasMessage("migration.already_applied_different_state");
  }

  private int insertRound(UUID rank, UUID entry, long number, UUID command, UUID author, byte[] input) {
    return jdbc.update("""
        insert into ranking_movie_round(ranking_id,entry_id,round_id,round_number,created_at,revealed,
           creation_command_id,creation_author_id,creation_input_fingerprint) values (?,?,?,?,now(),false,?,?,?)
        """, rank, entry, UUID.randomUUID(), number, command, author, input);
  }

  private void insertEntry(UUID rank, UUID entry, UUID movie) {
    assertThat(jdbc.update("""
        insert into ranking_movie_entry(ranking_id,entry_id,movie_id,created_at) values (?,?,?,now())
        """, rank, entry, movie)).isEqualTo(1);
  }

  private UUID identify(UUID rank, UUID account) {
    UUID author = UUID.randomUUID();
    jdbc.update("""
        insert into ranking_author_identity(ranking_id,author_id,account_id,deleted_public_code) values (?,?,?,?)
        """, rank, author, account, UUID.randomUUID());
    return author;
  }

  private UUID author(UUID rank, UUID account) {
    return jdbc.queryForObject("select author_id from ranking_author_identity where ranking_id=? and account_id=?",
        UUID.class, rank, account);
  }

  private UUID rank(UUID owner) {
    UUID rank = UUID.randomUUID();
    transactions.executeWithoutResult(status -> {
      jdbc.update("""
          insert into ranking(id,owner_user_id,visibility,admission,created_at) values (?,?,'PRIVATE','INVITATION',now())
          """, rank, owner);
      addMember(rank, owner);
    });
    return rank;
  }

  private void addMember(UUID rank, UUID account) {
    jdbc.update("insert into ranking_membership(ranking_id,user_id,active,created_at) values (?,?,true,now())",
        rank, account);
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

  private List<String> oldRoundRows() {
    return rows("ranking_movie_round", "creation_command_id", "creation_author_id", "creation_input_fingerprint");
  }

  private List<String> rows(String table, String... additions) {
    String json = "to_jsonb(r)";
    for (String addition : additions) json += " - '" + addition + "'";
    return jdbc.queryForList("select (" + json + ")::text from " + table + " r order by 1", String.class);
  }

  private List<String> roundForeignKeys() {
    return jdbc.queryForList("""
        select conname||':'||pg_get_constraintdef(oid) from pg_constraint
        where conrelid='ranking_movie_round'::regclass and contype='f' order by conname
        """, String.class);
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
}
