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
    private String historicalLocation;
    @TempDir Path temporary;

    @BeforeEach
    void createIsolatedSchemaInExplicitTestDatabase() throws Exception {
        url = System.getenv("TEST_DATABASE_URL");
        user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
        password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
        assertThat(url).as("TEST_DATABASE_URL must name a disposable local PostgreSQL database")
                .matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*(\\?.*)?");
        // Historical probes intentionally exercise the immutable V1 in isolation.
        // Copy the actual migration bytes rather than maintaining a second SQL baseline.
        Path historicalMigrations = Files.createDirectory(temporary.resolve("historical-v1"));
        try (var input = new ClassPathResource("db/migration/V1__initial_schema.sql").getInputStream()) {
            Files.copy(input, historicalMigrations.resolve("V1__initial_schema.sql"));
        }
        historicalLocation = "filesystem:" + historicalMigrations;
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
        Flyway flyway = latestFlyway();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(10);
        assertThat(count("select count(*) from information_schema.tables where table_schema='" + schema + "'" )).isEqualTo(24);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='1' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='2' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='3' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='4' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='5' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='6' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='7' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='8' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='9' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='10' and success")).isEqualTo(1);
        validateJpa();
        List<String> before = catalog();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(catalog()).isEqualTo(before);
        assertThatThrownBy(flyway::clean).isInstanceOf(FlywayException.class);
    }

    @Test
    void completeCatalogMatchesHistoricalExportIncludingAllTwelveForeignKeys() throws Exception {
        configuration().target("1").load().migrate();
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
        assertThatThrownBy(() -> historicalFlyway().migrate()).isInstanceOf(FlywayException.class).hasMessageContaining("non-empty");
        assertThat(count("select count(*) from " + schema + ".user_invites")).isEqualTo(1);
        historicalFlyway().baseline();
        assertThat(catalog()).isEqualTo(before);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='1' and type='BASELINE'")).isEqualTo(1);
        assertThat(historicalFlyway("classpath:db/fixture/upgrade").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(count("select id from " + schema + ".user_invites where code='synthetic-invite'")).isEqualTo(1);
        assertThat(count("insert into " + schema + ".user_invites(code) values ('second-synthetic-invite') returning id")).isEqualTo(2);
        assertThat(count("select count(*) from " + schema + ".migration_probe")).isEqualTo(1);
        validateJpa();
    }

    @Test
    void explicitlyAdoptedDevelopSchemaPreservesCatalogAndTranslatesOnlyKnownWishlistDuplicates() throws Exception {
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            ScriptUtils.executeSqlScript(connection, new org.springframework.core.io.ByteArrayResource(
                    SchemaExportTest.developLegacySchema().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            statement.execute("insert into user_invites(code) values ('synthetic-develop-invite')");
        }
        List<String> before = catalog();
        assertThat(before).anyMatch(definition -> definition.contains("ukt52x8vytfwq3vic32in8tb8y8"));
        assertThatThrownBy(() -> historicalFlyway().migrate()).isInstanceOf(FlywayException.class).hasMessageContaining("non-empty");
        historicalFlyway().baseline(); // Explicit adoption is permitted only for this inspected, disposable fixture.
        assertThat(historicalFlyway().migrate().migrationsExecuted).isZero();
        validateJpa();
        assertThat(catalog()).isEqualTo(before);

        var app = new org.springframework.boot.SpringApplication(com.moviemux.MovieMuxApplication.class);
        try (var context = app.run("--spring.profiles.active=test", "--server.address=127.0.0.1", "--server.port=0",
                "--spring.datasource.url=" + url, "--spring.datasource.username=" + user,
                "--spring.datasource.password=" + password, "--spring.datasource.hikari.schema=" + schema,
                "--spring.flyway.default-schema=" + schema, "--spring.flyway.schemas=" + schema,
                "--spring.flyway.locations=" + historicalLocation, "--spring.flyway.target=1",
                "--spring.jpa.properties.hibernate.default_schema=" + schema)) {
            var users = context.getBean(com.moviemux.user.adapter.repository.UserRepository.class);
            var wishlists = context.getBean(com.moviemux.wishlist.domain.repository.WishlistRepository.class);
            var owner = users.saveAndFlush(com.moviemux.user.adapter.repository.postgres.entity.UserEntity.builder()
                    .name("Synthetic legacy owner").email("legacy-" + UUID.randomUUID() + "@example.invalid")
                    .password("synthetic-hash").build());
            var wishlist = com.moviemux.wishlist.domain.model.Wishlist.builder()
                    .name("Legacy duplicate").shareable(false).moviesWishlists(List.of())
                    .user(new com.moviemux.wishlist.domain.model.WishlistOwner(owner.getId(), owner.getName())).build();
            var original = wishlists.save(wishlist);
            Throwable duplicate = catchThrowable(() -> wishlists.save(wishlist));
            assertThat(duplicate).isInstanceOf(com.moviemux.wishlist.domain.exception.WishlistPersistenceConflict.class);
            Throwable cause = duplicate;
            while (cause != null && !(cause instanceof org.hibernate.exception.ConstraintViolationException)) {
                cause = cause.getCause();
            }
            assertThat(cause).isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
            var violation = (org.hibernate.exception.ConstraintViolationException) cause;
            assertThat(violation.getSQLState()).isEqualTo("23505");
            assertThat(violation.getConstraintName()).isEqualTo("ukt52x8vytfwq3vic32in8tb8y8");
            wishlist.setUser(new com.moviemux.wishlist.domain.model.WishlistOwner(UUID.randomUUID(), "Missing owner"));
            assertThatThrownBy(() -> wishlists.save(wishlist))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                    .isNotInstanceOf(com.moviemux.wishlist.domain.exception.WishlistPersistenceConflict.class);
            assertThat(wishlists.findByUserIdOrderByCreatedAtDesc(owner.getId()))
                    .extracting(com.moviemux.wishlist.domain.model.Wishlist::getId).containsExactly(original.getId());
        }
        assertThat(catalog()).isEqualTo(before);
        assertThat(count("select id from " + schema + ".user_invites where code='synthetic-develop-invite'")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='1' and type='BASELINE' and success")).isEqualTo(1);
    }

    @Test
    void legacyMembershipUniqueCanBeReportedBeforePrimaryKey() throws Exception {
        flyway().migrate();
        String owner = UUID.randomUUID().toString();
        String wishlist = UUID.randomUUID().toString();
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            // Equivalent final constraints, with the legacy UNIQUE created first. PostgreSQL
            // may report either equivalent index; error translation must not rely on their order.
            statement.execute("alter table movie_wishlist_user_wishlist drop constraint movie_wishlist_user_wishlist_pkey");
            statement.execute("alter table movie_wishlist_user_wishlist add constraint ukmdk01guha2tx3rf192hcny1gm unique (wishlist_id, movie_id)");
            statement.execute("alter table movie_wishlist_user_wishlist add constraint movie_wishlist_user_wishlist_pkey primary key (movie_id, wishlist_id)");
            statement.execute("insert into \"user\"(id,created_at,email,name,password) values ('" + owner
                    + "',now(),'synthetic-membership@example.invalid','Synthetic','synthetic-hash')");
            statement.execute("insert into user_wishlist(id,created_at,name,shareable,user_id) values ('" + wishlist
                    + "',now(),'Synthetic',false,'" + owner + "')");
            statement.execute("insert into movie_wishlist(tmdb_id,url_image) values (900001,'synthetic')");
            String insert = "insert into movie_wishlist_user_wishlist(movie_id,wishlist_id) values (900001,'" + wishlist + "')";
            statement.execute(insert);
            Throwable duplicate = catchThrowable(() -> statement.execute(insert));
            assertThat(duplicate).isInstanceOf(org.postgresql.util.PSQLException.class);
            var violation = (org.postgresql.util.PSQLException) duplicate;
            assertThat(violation.getSQLState()).isEqualTo("23505");
            assertThat(violation.getServerErrorMessage().getConstraint()).isEqualTo("ukmdk01guha2tx3rf192hcny1gm");
        }
    }

    @Test
    void changedAppliedMigrationChecksumFailsWithoutRepair() throws Exception {
        latestFlyway().migrate();
        Path changedMigrations = Files.createDirectory(temporary.resolve("changed-migrations"));
        // Keep every applied version present so checksum mismatch is the only intended drift.
        for (var resource : new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__*.sql")) {
            try (var input = resource.getInputStream()) {
                Files.copy(input, changedMigrations.resolve(java.util.Objects.requireNonNull(resource.getFilename())));
            }
        }
        Path v1 = changedMigrations.resolve("V1__initial_schema.sql");
        String migration = Files.readString(v1);
        assertThat(migration).contains("varchar(100)");
        Files.writeString(v1, migration.replace("varchar(100)", "varchar(101)"));
        Flyway changed = configuration().locations("filesystem:" + changedMigrations).load();
        assertThatThrownBy(changed::migrate).isInstanceOf(FlywayException.class).hasMessageContaining("checksum mismatch");
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where success")).isEqualTo(10);
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
        assertThatThrownBy(() -> historicalFlyway("classpath:db/fixture/upgrade", "classpath:db/fixture/failure").migrate())
                .isInstanceOf(FlywayException.class);
        assertThat(count("select count(*) from " + schema + ".migration_probe")).isEqualTo(1);
        assertThat(count("select count(*) from information_schema.tables where table_schema='" + schema + "' and table_name='must_rollback'")).isZero();
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where success")).isEqualTo(2);
        assertThat(historicalFlyway("classpath:db/fixture/upgrade").migrate().migrationsExecuted).isZero();
    }

    @Test
    void deferredConstraintFailureAtCommitRollsBackMigration() throws Exception {
        historicalFlyway().migrate();
        assertThatThrownBy(() -> historicalFlyway("classpath:db/fixture/commit-failure").migrate()).isInstanceOf(FlywayException.class);
        assertThat(count("select count(*) from information_schema.tables where table_schema='" + schema + "' and table_name='commit_parent'")).isZero();
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where success")).isEqualTo(1);
    }

    @Test
    void concurrentStartersApplyVersionOnlyOnce() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> migrateAfter(start));
        var second = CompletableFuture.supplyAsync(() -> migrateAfter(start));
        start.countDown();
        assertThat(first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS)).isEqualTo(10);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='1' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='2' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='3' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='4' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='5' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='6' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='7' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='8' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='9' and success")).isEqualTo(1);
        assertThat(count("select count(*) from " + schema + ".flyway_schema_history where version='10' and success")).isEqualTo(1);
    }

    @Test
    void lockContentionFailsWithinConfiguredRetryBudgetWithoutPartialHistory() throws Exception {
        historicalFlyway().migrate();
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
        var first = CompletableFuture.supplyAsync(() -> configuration().locations(historicalLocation, "classpath:db/fixture/upgrade")
                .callbacks(pause).load().migrate().migrationsExecuted);
        try {
            assertThat(lockHeld.await(15, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> configuration().locations(historicalLocation, "classpath:db/fixture/upgrade")
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
        return latestFlyway().migrate().migrationsExecuted;
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration configuration() {
        return Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                .createSchemas(false).cleanDisabled(true).baselineOnMigrate(false).baselineVersion("1")
                .validateOnMigrate(true).outOfOrder(false).ignoreMigrationPatterns(new String[0]).failOnMissingLocations(true).validateMigrationNaming(true).locations("classpath:db/migration");
    }

    private Flyway flyway() {
        return configuration().load();
    }

    private Flyway historicalFlyway(String... additional) {
        List<String> locations = new ArrayList<>(); locations.add(historicalLocation); locations.addAll(List.of(additional));
        return configuration().locations(locations.toArray(String[]::new)).load();
    }

    private Flyway latestFlyway() {
        return configuration().locations("classpath:db/migration").load();
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
