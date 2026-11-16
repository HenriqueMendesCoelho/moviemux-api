package com.moviemux.migration;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import static org.assertj.core.api.Assertions.assertThat;

class SchemaExportTest {
    @Test
    void initialMigrationMatchesImmutableHistoricalHibernateMetadata() throws Exception {
        String generated = baseline();
        try (var input = getClass().getResourceAsStream("/db/migration/V1__initial_schema.sql")) {
            assertThat(input).isNotNull();
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(statements(migration)).containsExactlyInAnyOrderElementsOf(statements(generated));
            assertThat(migration.toLowerCase()).doesNotContain("if not exists", "alter table if exists", "insert into", "drop table");
            assertThat(statements(migration)).hasSize(25);
        }
    }

    static String baseline() throws Exception {
        try (var input = SchemaExportTest.class.getResourceAsStream("/db/baseline/hibernate-baseline.sql")) {
            assertThat(input).isNotNull();
            byte[] bytes = input.readAllBytes();
            assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .as("Immutable export matching published source 8e185d5f8f1ed6ea4f97df67a26f43d815850937")
                    .isEqualTo("062eda9e4b8b539a99c2ed87f40adda9da645f08e46828d5b6b98797036aa4d5");
            return new String(bytes, StandardCharsets.UTF_8).replace("alter table if exists ", "alter table ");
        }
    }

    static String developLegacySchema() throws Exception {
        try (var input = SchemaExportTest.class.getResourceAsStream("/db/baseline/develop-2b04279-schema.sql")) {
            assertThat(input).isNotNull();
            byte[] bytes = input.readAllBytes();
            assertThat(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))
                    .as("Synthetic schema derived from public develop 2b04279c4cb232d17e828e181fa51c26f0036550")
                    .isEqualTo("f885293b179ecc238c0bd309cd5dba732f16492546813afc3bdfcd1de2033196");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    @Test
    void developLegacyFixtureChangesOnlyTheEightProvenUniqueNames() throws Exception {
        String legacy = developLegacySchema();
        // Undo each exact named UNIQUE in this source-derived fixture, never ignore arbitrary names.
        legacy = legacy.replace("imdb_id varchar(255)", "imdb_id varchar(255) unique")
                .replace(", constraint UKgk23fo99ijqjqbe7fe75a7nva unique (imdb_id)", "")
                .replace("tmdb_id bigint, updated_at", "tmdb_id bigint unique, updated_at")
                .replace(", constraint UKbgtoou3ammfcnlke2mv6uo522 unique (tmdb_id)", "")
                .replace("message_id varchar(255)", "message_id varchar(255) unique")
                .replace(", constraint UK858gw1x05gabbh34facl798dv unique (message_id)", "")
                .replace("name varchar(255) not null", "name varchar(255) not null unique")
                .replace(", constraint UK5giymlkouxstuwpg94mnf9liq unique (name)", "")
                .replace("tmdb_id bigint, primary key (id)", "tmdb_id bigint unique, primary key (id)")
                .replace(", constraint UKk73u204hsmnpd0o2vd4sjeb6i unique (tmdb_id)", "")
                .replace("email varchar(100) not null", "email varchar(100) not null unique")
                .replace(", constraint UKhl4ga9r00rh51mdaf20hmnslt unique (email)", "")
                .replace("code varchar(255) not null", "code varchar(255) not null unique")
                .replace(", constraint UKoehwdfs8qvano6ci15e3wpqlm unique (code)", "")
                .replace("constraint UKt52x8vytfwq3vic32in8tb8y8 unique (user_id, name)", "unique (user_id, name)");
        assertThat(statements(legacy)).hasSize(25).containsExactlyInAnyOrderElementsOf(statements(baseline()));
    }

    @Test
    void currentMetadataPreservesDefinitionsWithExplicitSqlOwnedForeignKeysAndColumnOrdering() throws Exception {
        Set<String> expected = statements(baseline());
        Set<String> sqlOwned = Set.of(
                "alter table movie add constraint FKgga7gitf0nti7b7dh2nn9sb7 foreign key (user_id) references \"user\"",
                "alter table movie_note add constraint FK71dstqdlf5m3893skesoerp20 foreign key (user_id) references \"user\"",
                "alter table user_wishlist add constraint FKidfneg6f0w343dgjwxgpiubyb foreign key (user_id) references \"user\"");
        assertThat(expected).containsAll(sqlOwned);
        expected.removeAll(sqlOwned);
        // Scalar embedded-key mapping changes only Hibernate's export ordering, not V1's physical columns.
        String originalNote = "create table movie_note (created_at timestamp with time zone not null, note integer, updated_at timestamp with time zone, movie_id uuid not null, user_id uuid not null, primary key (movie_id, user_id))";
        String scalarNote = "create table movie_note (user_id uuid not null, created_at timestamp with time zone not null, note integer, updated_at timestamp with time zone, movie_id uuid not null, primary key (movie_id, user_id))";
        assertThat(expected.remove(originalNote)).isTrue();
        expected.add(scalarNote);
        assertThat(statements(SchemaMetadata.exportDdl().replace("alter table if exists ", "alter table ")))
                .hasSize(22).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static Set<String> statements(String sql) {
        String withoutComments = sql.lines().filter(line -> !line.stripLeading().startsWith("--")).collect(Collectors.joining("\n"));
        return Arrays.stream(withoutComments.split(";")).map(String::trim).filter(s -> !s.isBlank())
                .map(s -> s.replaceAll("\\s+", " ")).collect(Collectors.toCollection(TreeSet::new));
    }
}
