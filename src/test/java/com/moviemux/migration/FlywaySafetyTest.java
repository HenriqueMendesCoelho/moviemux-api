package com.moviemux.migration;

import com.moviemux.application.spring.configuration.FlywaySafetyConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FlywaySafetyTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(FlywaySafetyConfiguration.class)
            .withPropertyValues("spring.jpa.hibernate.ddl-auto=validate", "spring.sql.init.mode=never",
                    "spring.jpa.defer-datasource-initialization=false", "spring.flyway.enabled=true",
                    "spring.flyway.validate-on-migrate=true", "spring.flyway.baseline-on-migrate=false",
                    "spring.flyway.clean-disabled=true", "spring.flyway.out-of-order=false",
                    "spring.flyway.create-schemas=false", "spring.flyway.default-schema=public",
                    "spring.flyway.schemas=public", "spring.flyway.ignore-migration-patterns=",
                    "spring.flyway.fail-on-missing-locations=true", "spring.flyway.validate-migration-naming=true", "spring.jpa.properties.hibernate.default_schema=public");

    @Test
    void singleSchemaWriterConfigurationIsAcceptedWithoutOpeningADatabase() {
        context.run(result -> assertThat(result).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.jpa.hibernate.ddl-auto=update", "spring.jpa.hibernate.ddl-auto=create",
            "spring.sql.init.mode=always", "spring.jpa.defer-datasource-initialization=true",
            "spring.flyway.enabled=false", "spring.flyway.validate-on-migrate=false",
            "spring.flyway.baseline-on-migrate=true", "spring.flyway.clean-disabled=false",
            "spring.flyway.out-of-order=true", "spring.flyway.create-schemas=true",
            "spring.flyway.ignore-migration-patterns=*:missing", "spring.flyway.fail-on-missing-locations=false",
            "spring.flyway.validate-migration-naming=false", "spring.flyway.schemas=other", "spring.jpa.properties.hibernate.default_schema=other",
            "spring.jpa.properties.hibernate.hbm2ddl.auto=update",
            "spring.jpa.properties.jakarta.persistence.schema-generation.database.action=create"})
    void conflictingWritersAndUnsafeMigrationOverridesFailBeforeDatabaseInitialization(String override) {
        context.withPropertyValues(override).run(result -> assertThat(result).hasFailed());
    }
}
