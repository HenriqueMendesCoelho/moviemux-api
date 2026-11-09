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
