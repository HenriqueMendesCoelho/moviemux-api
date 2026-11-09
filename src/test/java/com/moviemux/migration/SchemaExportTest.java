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
    void initialMigrationMatchesCompleteOfflineHibernateMetadata() throws Exception {
        String generated = SchemaMetadata.exportDdl().replace("alter table if exists ", "alter table ");
        try (var input = getClass().getResourceAsStream("/db/migration/V1__initial_schema.sql")) {
            assertThat(input).isNotNull();
            String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(statements(migration)).containsExactlyInAnyOrderElementsOf(statements(generated));
            assertThat(migration.toLowerCase()).doesNotContain("if not exists", "alter table if exists", "insert into", "drop table");
            assertThat(statements(migration)).hasSize(25);
        }
    }

    private static Set<String> statements(String sql) {
        String withoutComments = sql.lines().filter(line -> !line.stripLeading().startsWith("--")).collect(Collectors.joining("\n"));
        return Arrays.stream(withoutComments.split(";")).map(String::trim).filter(s -> !s.isBlank())
                .map(s -> s.replaceAll("\\s+", " ")).collect(Collectors.toCollection(TreeSet::new));
    }
}
