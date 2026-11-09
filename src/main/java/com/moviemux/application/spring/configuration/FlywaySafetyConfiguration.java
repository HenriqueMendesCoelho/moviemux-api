package com.moviemux.application.spring.configuration;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Rejects conflicting schema writers before a datasource or persistence context is created. */
@Configuration(proxyBeanMethods = false)
public class FlywaySafetyConfiguration {
    @Bean
    static BeanFactoryPostProcessor flywaySafetyGuard(Environment environment) {
        return beanFactory -> {
            require(environment, "spring.jpa.hibernate.ddl-auto", "validate");
            require(environment, "spring.sql.init.mode", "never");
            require(environment, "spring.jpa.defer-datasource-initialization", "false");
            require(environment, "spring.flyway.enabled", "true");
            require(environment, "spring.flyway.validate-on-migrate", "true");
            require(environment, "spring.flyway.baseline-on-migrate", "false");
            require(environment, "spring.flyway.clean-disabled", "true");
            require(environment, "spring.flyway.out-of-order", "false");
            require(environment, "spring.flyway.create-schemas", "false");
            require(environment, "spring.flyway.ignore-migration-patterns", "");
            require(environment, "spring.flyway.fail-on-missing-locations", "true");
            require(environment, "spring.flyway.validate-migration-naming", "true");
            rejectOverride(environment, "spring.jpa.properties.hibernate.hbm2ddl.auto", "validate");
            rejectOverride(environment, "spring.jpa.properties.jakarta.persistence.schema-generation.database.action", "none");
            String schema = environment.getRequiredProperty("spring.flyway.default-schema");
            if (schema.isBlank() || !schema.equals(environment.getProperty("spring.flyway.schemas"))
                    || !schema.equals(environment.getProperty("spring.jpa.properties.hibernate.default_schema"))) {
                throw new IllegalStateException("Flyway and JPA must use the same explicit, existing schema");
            }
        };
    }

    private static void require(Environment environment, String property, String expected) {
        if (!expected.equals(environment.getProperty(property))) {
            throw new IllegalStateException("Unsafe database initialization configuration: " + property + " must be " + expected);
        }
    }

    private static void rejectOverride(Environment environment, String property, String expected) {
        if (environment.containsProperty(property)) require(environment, property, expected);
    }
}
