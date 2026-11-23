package com.moviemux.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
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

class LegacyMovieEligibilityMigrationIT {
  private String url, user, password, schema;
  private JdbcTemplate jdbc;
  private TransactionTemplate transactions;

  @BeforeEach void createDisposableSchema() throws SQLException {
    url = System.getenv("TEST_DATABASE_URL");
    assertThat(url).matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
    user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
    password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
    schema = "movie_eligibility_test_" + UUID.randomUUID().toString().replace("-", "");
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

  @Test void v11UpgradeAddsOnlyEligibilityAndPreservesLegacyMovieNotesAndDiscordRows() {
    assertThat(flyway("11").migrate().migrationsExecuted).isEqualTo(11);
    UUID actor = account(), oldMovie = legacyMovie();
    jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),null)", oldMovie, actor);
    jdbc.update("insert into movie_discord(movie_id,message_id) values (?,'synthetic-legacy')", oldMovie);
    var priorCatalog = catalog();
    var priorMovie = rows("movie");
    var priorNotes = rows("movie_note");
    var priorDiscord = rows("movie_discord");

    var v12 = flyway("12");
    assertThat(v12.migrate().migrationsExecuted).isEqualTo(1);
    var expectedCatalog = new ArrayList<>(priorCatalog);
    expectedCatalog.add("movie:legacy_eligible:boolean:NO:true");
    assertThat(catalog()).containsExactlyInAnyOrderElementsOf(expectedCatalog);
    assertThat(rows("movie")).isEqualTo(priorMovie);
    assertThat(rows("movie_note")).isEqualTo(priorNotes);
    assertThat(rows("movie_discord")).isEqualTo(priorDiscord);
    assertThat(isLegacyEligible(oldMovie)).isTrue();
    assertThat(isLegacyEligible(legacyMovie())).isTrue(); // The unchanged legacy INSERT has no flag.
    assertThat(v12.migrate().migrationsExecuted).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from flyway_schema_history where version='12' and type='SQL' and success", Integer.class))
        .isEqualTo(1);
  }

  @Test void eligibilityIsImmutableAndLegacyChildWritesRequireAnEligibleMovie() {
    flyway("12").migrate();
    UUID actor = account(), legacy = legacyMovie(), otherLegacy = legacyMovie();
    UUID contextual = UUID.randomUUID(), missing = UUID.randomUUID();
    jdbc.update("""
        insert into movie(id,created_at,description,director,english_title,portuguese_title,legacy_eligible)
        values (?,now(),'Synthetic','Synthetic','Synthetic','Synthetic',false)
        """, contextual);
    assertThat(isLegacyEligible(contextual)).isFalse();
    for (UUID id : List.of(legacy, contextual)) {
      expectDatabaseFailure("23514", "Movie eligibility cannot be changed", () ->
          jdbc.update("update movie set legacy_eligible = not legacy_eligible where id=?", id));
      assertThat(jdbc.update("update movie set legacy_eligible=legacy_eligible where id=?", id)).isEqualTo(1);
    }
    assertThat(isLegacyEligible(legacy)).isTrue();
    assertThat(isLegacyEligible(contextual)).isFalse();
    assertThat(jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),8)",
        legacy, actor)).isEqualTo(1);
    assertThat(jdbc.update("insert into movie_discord(movie_id,message_id) values (?,'synthetic-existing')",
        legacy)).isEqualTo(1);
    var permittedNote = rows("movie_note");
    var permittedDiscord = rows("movie_discord");

    for (UUID id : List.of(contextual, missing)) {
      expectDatabaseFailure("23503", "Movie reference is unavailable", () ->
          jdbc.update("insert into movie_note(movie_id,user_id,created_at,note) values (?,?,now(),8)", id, actor));
      expectDatabaseFailure("23503", "Movie reference is unavailable", () ->
          jdbc.update("insert into movie_discord(movie_id,message_id) values (?,'synthetic-blocked')", id));
      expectDatabaseFailure("23503", "Movie reference is unavailable", () ->
          jdbc.update("update movie_note set movie_id=? where movie_id=? and user_id=?", id, legacy, actor));
      expectDatabaseFailure("23503", "Movie reference is unavailable", () ->
          jdbc.update("update movie_discord set movie_id=? where movie_id=?", id, legacy));
    }
    assertThat(rows("movie_note")).isEqualTo(permittedNote);
    assertThat(rows("movie_discord")).isEqualTo(permittedDiscord);
    assertThat(jdbc.update("update movie_note set note=9 where movie_id=? and user_id=?", legacy, actor)).isEqualTo(1);
    assertThat(jdbc.update("update movie_discord set message_id='synthetic-edit' where movie_id=?", legacy)).isEqualTo(1);
    assertThat(jdbc.update("update movie_note set movie_id=? where movie_id=? and user_id=?",
        otherLegacy, legacy, actor)).isEqualTo(1);
    assertThat(jdbc.update("update movie_discord set movie_id=? where movie_id=?", otherLegacy, legacy)).isEqualTo(1);

    // The boundary applies to legacy children, not canonical references in contextual rankings.
    UUID ranking = UUID.randomUUID();
    transactions.executeWithoutResult(status -> {
      jdbc.update("""
          insert into ranking(id,owner_user_id,visibility,admission,created_at)
          values (?,?,'PRIVATE','INVITATION',now())
          """, ranking, actor);
      jdbc.update("insert into ranking_membership(ranking_id,user_id,active,created_at) values (?,?,true,now())",
          ranking, actor);
      assertThat(jdbc.update("""
          insert into ranking_movie_entry(ranking_id,entry_id,movie_id,created_at) values (?,?,?,now())
          """, ranking, UUID.randomUUID(), contextual)).isEqualTo(1);
    });
  }

  @Test void v12UpgradeAllowsMissingContextualMetadataButPreservesAllLegacyRequirements() {
    assertThat(flyway("12").migrate().migrationsExecuted).isEqualTo(12);
    UUID inherited = legacyMovie(), contextual = UUID.randomUUID();
    jdbc.update("""
        insert into movie(id,created_at,description,director,english_title,portuguese_title,legacy_eligible)
        values (?,now(),'Synthetic','Synthetic','Synthetic','Synthetic',false)
        """, contextual);
    var oldRows = rows("movie");
    var oldCatalog = catalog();

    var v13 = flyway("13");
    assertThat(v13.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(v13.info().current().getVersion().getVersion()).isEqualTo("13");
    assertThat(rows("movie")).isEqualTo(oldRows);
    assertThat(isLegacyEligible(inherited)).isTrue();
    assertThat(isLegacyEligible(contextual)).isFalse();
    String checkPrefix = "movie:movie_legacy_required_metadata_ck:";
    var newCatalog = catalog();
    assertThat(newCatalog.stream().filter(definition -> definition.startsWith(checkPrefix)).toList())
        .singleElement().satisfies(definition -> assertThat(definition).startsWith(checkPrefix + "CHECK "));
    var allowedCatalog = new ArrayList<>(oldCatalog);
    for (String old : List.of("movie:description:text:NO:", "movie:director:character varying:NO:",
        "movie:english_title:character varying:NO:", "movie:portuguese_title:character varying:NO:")) {
      assertThat(allowedCatalog.remove(old)).isTrue();
      allowedCatalog.add(old.replace(":NO:", ":YES:"));
    }
    assertThat(newCatalog.stream().filter(definition -> !definition.startsWith(checkPrefix)).toList())
        .containsExactlyInAnyOrderElementsOf(allowedCatalog);
    assertThat(jdbc.queryForList("""
        select column_name||':'||data_type||':'||coalesce(character_maximum_length::text,'')||':'||is_nullable
        from information_schema.columns where table_schema=? and table_name='movie'
        and column_name in ('description','director','english_title','portuguese_title') order by column_name
        """, String.class, schema)).containsExactly("description:text::YES", "director:character varying:150:YES",
            "english_title:character varying:150:YES", "portuguese_title:character varying:150:YES");

    for (String missing : List.of("description", "director", "english_title", "portuguese_title")) {
      expectMetadataConstraint(() -> jdbc.update("update movie set " + missing + "=null where id=?", inherited));
      expectMetadataConstraint(() -> jdbc.update("""
          insert into movie(id,created_at,description,director,english_title,portuguese_title)
          values (?,now(),?,?,?,?)
          """, UUID.randomUUID(), legacyValueExcept("description", missing), legacyValueExcept("director", missing),
          legacyValueExcept("english_title", missing), legacyValueExcept("portuguese_title", missing)));
    }
    assertThat(rows("movie")).isEqualTo(oldRows);

    UUID partialMovie = UUID.randomUUID();
    assertThat(jdbc.update("""
        insert into movie(id,created_at,english_title,legacy_eligible)
        values (?,now(),'Verified provider title',false)
        """, partialMovie)).isEqualTo(1);
    assertThat(jdbc.queryForMap("select description,director,english_title,portuguese_title from movie where id=?",
        partialMovie)).containsEntry("description", null).containsEntry("director", null)
        .containsEntry("english_title", "Verified provider title").containsEntry("portuguese_title", null);
    assertThat(jdbc.update("update movie set english_title=null where id=?", partialMovie)).isEqualTo(1);
    assertThat(isLegacyEligible(partialMovie)).isFalse();
    assertThat(isLegacyEligible(legacyMovie())).isTrue();
    assertThat(v13.migrate().migrationsExecuted).isZero();
    assertThat(catalog()).isEqualTo(newCatalog);
  }

  private String legacyValueExcept(String field, String missing) {
    return field.equals(missing) ? null : "Synthetic";
  }

  private void expectMetadataConstraint(Runnable statement) {
    assertThatThrownBy(statement::run).hasRootCauseInstanceOf(PSQLException.class)
        .satisfies(error -> {
          var postgres = (PSQLException) error.getCause();
          assertThat(postgres.getSQLState()).isEqualTo("23514");
          assertThat(postgres.getServerErrorMessage().getConstraint()).isEqualTo("movie_legacy_required_metadata_ck");
        });
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

  private UUID legacyMovie() {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into movie(id,created_at,description,director,english_title,portuguese_title)
        values (?,now(),'Synthetic','Synthetic','Synthetic','Synthetic')
        """, id);
    return id;
  }

  private Boolean isLegacyEligible(UUID movie) {
    return jdbc.queryForObject("select legacy_eligible from movie where id=?", Boolean.class, movie);
  }

  private List<String> rows(String table) {
    String row = table.equals("movie") ? "(to_jsonb(r) - 'legacy_eligible')::text" : "to_jsonb(r)::text";
    return jdbc.queryForList("select " + row + " from " + table + " r order by 1", String.class);
  }

  private List<String> catalog() {
    var result = new ArrayList<String>();
    result.addAll(jdbc.queryForList("""
        select table_name||':'||column_name||':'||data_type||':'||is_nullable||':'||coalesce(column_default,'')
        from information_schema.columns where table_schema=?
        and table_name in ('movie','movie_note','movie_discord') order by table_name,ordinal_position
        """, String.class, schema));
    result.addAll(jdbc.queryForList("""
        select c.relname||':'||con.conname||':'||pg_get_constraintdef(con.oid)
        from pg_constraint con join pg_class c on c.oid=con.conrelid
        join pg_namespace n on n.oid=c.relnamespace where n.nspname=?
        and c.relname in ('movie','movie_note','movie_discord') order by c.relname,con.conname
        """, String.class, schema));
    result.addAll(jdbc.queryForList("""
        select tablename||':'||indexname||':'||indexdef from pg_indexes where schemaname=?
        and tablename in ('movie','movie_note','movie_discord') order by tablename,indexname
        """, String.class, schema));
    return result;
  }

  private void expectDatabaseFailure(String sqlState, String message, Runnable statement) {
    assertThatThrownBy(statement::run).hasRootCauseInstanceOf(PSQLException.class)
        .satisfies(error -> {
          var postgres = (PSQLException) error.getCause();
          assertThat(postgres.getSQLState()).isEqualTo(sqlState);
          assertThat(postgres.getServerErrorMessage().getMessage()).isEqualTo(message);
          assertThat(postgres.getServerErrorMessage().getDetail()).isNull();
        });
  }
}
