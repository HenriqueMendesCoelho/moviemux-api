package com.moviemux.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class FlywayMigrationIT {
    private String url;
    private String user;
    private String password;
    private String schema;
    @TempDir Path temporary;

    @BeforeEach
    void createIsolatedSchemaInExplicitTestDatabase() throws Exception {
        url = System.getenv("TEST_DATABASE_URL");
        user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
        password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
        assertThat(url).as("TEST_DATABASE_URL must name a disposable local PostgreSQL database")
                .matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*(\\?.*)?");
        schema = "migration_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connection()) { connection.createStatement().execute("create schema " + schema); }
    }

    @AfterEach
    void removeOnlyTheSchemaCreatedByThisTest() throws Exception {
        if (schema != null) try (Connection connection = connection()) {
            connection.createStatement().execute("drop schema " + schema + " cascade");
        }
    }

    @Test
    void emptyDatabaseMigratesBeforeJpaValidationAndRestartDoesNotReapply() throws Exception {
        Flyway flyway = flyway();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(count("select count(*) from information_schema.tables where table_schema='" + schema + "'" )).isEqualTo(14);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='1' and success")).isEqualTo(1);
        validateJpa();
        List<String> before = catalog();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(catalog()).isEqualTo(before);
        assertThatThrownBy(flyway::clean).isInstanceOf(FlywayException.class);
    }

    @Test
    void completeCatalogMatchesHistoricalExportIncludingAllTwelveForeignKeys() throws Exception {
        flyway().migrate();
        String historical = schema + "_historical";
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("create schema " + historical);
            try {
                statement.execute("set search_path to " + historical);
                for (String ddl : SchemaExportTest.baseline().split(";")) {
                    if (!ddl.isBlank()) statement.execute(ddl);
                }
                assertThat(count("select count(*) from information_schema.tables where table_schema='" + schema
                        + "' and table_name <> 'flyway_schema_history'")).isEqualTo(13);
                assertThat(count("select count(*) from pg_constraint c join pg_namespace n on n.oid=c.connamespace where n.nspname='"
                        + schema + "' and c.contype='f'")).isEqualTo(12);
                assertThat(catalog()).containsExactlyElementsOf(catalog(historical));
            } finally {
                statement.execute("drop schema " + historical + " cascade");
            }
        }
    }

    @Test
    void sqlOwnedUserForeignKeysRejectOrphanOwnersAndRatings() throws Exception {
        flyway().migrate();
        String missingUser = UUID.randomUUID().toString();
        String movie = UUID.randomUUID().toString();
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            statement.execute("insert into movie(id,created_at,description,director,english_title,portuguese_title) values ('"
                    + movie + "',now(),'synthetic','synthetic','synthetic','synthetic')");
            for (String sql : List.of(
                    "update movie set user_id='" + missingUser + "' where id='" + movie + "'",
                    "insert into movie_note(movie_id,user_id,created_at,note) values ('" + movie + "','" + missingUser + "',now(),5)",
                    "insert into user_wishlist(id,user_id,created_at,shareable) values ('" + UUID.randomUUID() + "','" + missingUser + "',now(),false)")) {
                assertThatThrownBy(() -> statement.execute(sql)).isInstanceOf(java.sql.SQLException.class)
                        .satisfies(error -> assertThat(((java.sql.SQLException) error).getSQLState()).isEqualTo("23503"));
            }
            assertThat(count("select count(*) from " + schema + ".movie where user_id is null")).isEqualTo(1);
            assertThat(count("select count(*) from " + schema + ".movie_note")).isZero();
            assertThat(count("select count(*) from " + schema + ".user_wishlist")).isZero();
        }
    }

    @Test
    void existingSchemaRequiresExplicitBaselineAndPreservesRowsIdentityAndCatalog() throws Exception {
        try (Connection connection = connection()) {
            connection.createStatement().execute("set search_path to " + schema);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V1__initial_schema.sql"));
            connection.createStatement().execute("insert into user_invites(code) values ('synthetic-invite')");
        }
        List<String> before = catalog();
        assertThatThrownBy(() -> flyway().migrate()).isInstanceOf(FlywayException.class).hasMessageContaining("non-empty");
        assertThat(count("select count(*) from " + schema + ".user_invites")).isEqualTo(1);
        flyway().baseline();
        assertThat(catalog()).isEqualTo(before);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='1' and type='BASELINE'")).isEqualTo(1);
        assertThat(flyway("classpath:db/fixture/upgrade").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(count("select id from " + schema + ".user_invites where code='synthetic-invite'")).isEqualTo(1);
        assertThat(count("insert into " + schema + ".user_invites(code) values ('second-synthetic-invite') returning id")).isEqualTo(2);
        assertThat(count("select count(*) from " + schema + ".migration_probe")).isEqualTo(1);
        validateJpa();
    }

    @Test
    void changedAppliedMigrationChecksumFailsWithoutRepair() throws Exception {
        flyway().migrate();
        String migration = new String(new ClassPathResource("db/migration/V1__initial_schema.sql").getInputStream().readAllBytes());
        Files.writeString(temporary.resolve("V1__initial_schema.sql"), migration.replace("varchar(100)", "varchar(101)"));
        Flyway changed = configuration().locations("filesystem:" + temporary).load();
        assertThatThrownBy(changed::migrate).isInstanceOf(FlywayException.class).hasMessageContaining("checksum mismatch");
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where success")).isEqualTo(1);
    }

    @Test
    void nonexistentSchemaIsNotCreatedImplicitly() {
        Flyway wrong = configuration().schemas(schema + "_wrong").defaultSchema(schema + "_wrong").load();
        assertThatThrownBy(wrong::migrate).isInstanceOf(FlywayException.class);
        assertThatCode(() -> {
            assertThat(count("select count(*) from information_schema.schemata where schema_name='" + schema + "_wrong'")).isZero();
        }).doesNotThrowAnyException();
    }

    @Test
    void catalogComparisonDetectsConstraintDriftThatMigrationHistoryDoesNot() throws Exception {
        flyway().migrate();
        List<String> expected = catalog();
        try (Connection connection = connection()) {
            connection.createStatement().execute("alter table " + schema + ".\"user\" alter column name drop not null");
        }
        assertThatCode(() -> flyway().validate()).doesNotThrowAnyException();
        assertThat(catalog()).isNotEqualTo(expected);
        assertThatThrownBy(() -> assertCatalogMatches(expected)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Schema drift");
    }

    @Test
    void missingSqlOwnedForeignKeyIsDetectedEvenWhenFlywayAndJpaValidate() throws Exception {
        flyway().migrate();
        List<String> expected = catalog();
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("alter table " + schema + ".movie drop constraint FKgga7gitf0nti7b7dh2nn9sb7");
        }
        assertThatCode(() -> flyway().validate()).doesNotThrowAnyException();
        assertThatCode(this::validateJpa).doesNotThrowAnyException();
        assertThatThrownBy(() -> assertCatalogMatches(expected)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Schema drift");
    }

    @Test
    void missingMappedColumnFailsJpaValidationAfterFlyway() throws Exception {
        flyway().migrate();
        try (Connection connection = connection()) {
            connection.createStatement().execute("alter table " + schema + ".\"user\" drop column name");
        }
        assertThatCode(() -> flyway().validate()).doesNotThrowAnyException();
        assertThatThrownBy(this::validateJpa).hasMessageContaining("missing column");
    }

    @Test
    void failedLaterMigrationKeepsEarlierCommitButRollsBackItsOwnDdl() throws Exception {
        assertThatThrownBy(() -> flyway("classpath:db/fixture/upgrade", "classpath:db/fixture/failure").migrate())
                .isInstanceOf(FlywayException.class);
        assertThat(count("select count(*) from " + schema + ".migration_probe")).isEqualTo(1);
        assertThat(count("select count(*) from information_schema.tables where table_schema='" + schema + "' and table_name='must_rollback'")).isZero();
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where success")).isEqualTo(2);
        assertThat(flyway("classpath:db/fixture/upgrade").migrate().migrationsExecuted).isZero();
    }

    @Test
    void deferredConstraintFailureAtCommitRollsBackMigration() throws Exception {
        flyway().migrate();
        assertThatThrownBy(() -> flyway("classpath:db/fixture/commit-failure").migrate()).isInstanceOf(FlywayException.class);
        assertThat(count("select count(*) from information_schema.tables where table_schema='" + schema + "' and table_name='commit_parent'")).isZero();
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where success")).isEqualTo(1);
    }

    @Test
    void concurrentStartersApplyVersionOnlyOnce() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> migrateAfter(start));
        var second = CompletableFuture.supplyAsync(() -> migrateAfter(start));
        start.countDown();
        assertThat(first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='1' and success")).isEqualTo(1);
    }

    @Test
    void lockContentionFailsWithinConfiguredRetryBudgetWithoutPartialHistory() throws Exception {
        flyway().migrate();
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Callback pause = new Callback() {
            public boolean supports(Event event, Context context) { return event == Event.BEFORE_EACH_MIGRATE; }
            public boolean canHandleInTransaction(Event event, Context context) { return true; }
            public String getCallbackName() { return "synthetic-lock-holder"; }
            public void handle(Event event, Context context) {
                lockHeld.countDown();
                try { if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("Test lock timed out"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
        };
        var first = CompletableFuture.supplyAsync(() -> configuration().locations("classpath:db/migration", "classpath:db/fixture/upgrade")
                .callbacks(pause).load().migrate().migrationsExecuted);
        try {
            assertThat(lockHeld.await(15, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> configuration().locations("classpath:db/migration", "classpath:db/fixture/upgrade")
                    .lockRetryCount(0).load().migrate()).isInstanceOf(FlywayException.class).hasMessageContaining("lock");
        } finally { release.countDown(); }
        assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='2' and success")).isEqualTo(1);
    }

    @Test
    void migrationFailurePreventsApplicationReadinessAndJpaCreation() throws Exception {
        try (Connection connection = connection()) {
            connection.createStatement().execute("create table " + schema + ".legacy_sentinel(id integer)");
            connection.createStatement().execute("insert into " + schema + ".legacy_sentinel values (42)");
        }
        var ready = new java.util.concurrent.atomic.AtomicBoolean();
        var app = new org.springframework.boot.SpringApplication(com.moviemux.MovieMuxApplication.class);
        app.addListeners((org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationReadyEvent>)
                event -> ready.set(true));
        assertThatThrownBy(() -> app.run("--spring.profiles.active=test", "--server.port=0",
                "--spring.datasource.url=" + url, "--spring.datasource.username=" + user,
                "--spring.datasource.password=" + password, "--spring.flyway.default-schema=" + schema,
                "--spring.flyway.schemas=" + schema, "--spring.jpa.properties.hibernate.default_schema=" + schema))
                .hasStackTraceContaining("non-empty");
        assertThat(ready).isFalse();
        assertThat(count("select id from " + schema + ".legacy_sentinel")).isEqualTo(42);
        assertThat(count("select count(*) from information_schema.tables where table_schema='" + schema + "' and table_name='flyway_schema_history'")).isZero();
    }

    private int migrateAfter(CountDownLatch start) {
        try { if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Start latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        return flyway().migrate().migrationsExecuted;
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration configuration() {
        return Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).baselineVersion("1")
                .validateOnMigrate(true).outOfOrder(false).ignoreMigrationPatterns(new String[0]).failOnMissingLocations(true).validateMigrationNaming(true).locations("classpath:db/migration");
    }

    private Flyway flyway(String... additional) {
        List<String> locations = new ArrayList<>(); locations.add("classpath:db/migration"); locations.addAll(List.of(additional));
        return configuration().locations(locations.toArray(String[]::new)).load();
    }

    private Connection connection() throws Exception { return DriverManager.getConnection(url, user, password); }

    private long count(String sql) throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue(); return result.getLong(1);
        }
    }

    private void validateJpa() throws Exception {
        Map<String, Object> settings = SchemaMetadata.settings();
        settings.put("hibernate.connection.url", url); settings.put("hibernate.connection.username", user);
        settings.put("hibernate.connection.password", password); settings.put("hibernate.default_schema", schema);
        settings.put("hibernate.hbm2ddl.auto", "validate");
        try (var registry = new StandardServiceRegistryBuilder().applySettings(settings).build()) {
            SchemaManagementToolCoordinator.process(SchemaMetadata.metadata(registry), registry, settings, action -> {});
        }
    }

    private void assertCatalogMatches(List<String> expected) throws Exception {
        if (!catalog().equals(expected)) throw new IllegalStateException("Schema drift requires review before baseline");
    }

    private List<String> catalog() throws Exception { return catalog(schema); }

    private List<String> catalog(String schema) throws Exception {
        List<String> definitions = new ArrayList<>();
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            for (String query : List.of(
                    "select table_name || ':' || column_name || ':' || data_type || ':' || udt_name || ':' || coalesce(character_maximum_length::text,'') || ':' || is_nullable || ':' || coalesce(column_default,'') || ':' || is_identity || ':' || coalesce(identity_generation,'') from information_schema.columns where table_schema='" + schema + "' and table_name <> 'flyway_schema_history' order by table_name,ordinal_position",
                    "select c.relname || ':' || con.conname || ':' || pg_get_constraintdef(con.oid) from pg_constraint con join pg_class c on c.oid=con.conrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname='" + schema + "' and c.relname <> 'flyway_schema_history' order by c.relname,con.conname",
                    "select tablename || ':' || indexname || ':' || indexdef from pg_indexes where schemaname='" + schema + "' and tablename <> 'flyway_schema_history' order by tablename,indexname")) {
                try (ResultSet result = statement.executeQuery(query)) { while (result.next()) definitions.add(result.getString(1).replace(schema + ".", "<schema>.")); }
            }
        }
        return definitions;
    }
}
