package com.moviemux;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.test.context.ActiveProfiles("test")
@SpringBootTest
class MovieMuxApplicationIT {
    @DynamicPropertySource
    static void requireDisposableLocalDatabaseBeforeContextInitialization(DynamicPropertyRegistry properties) {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url == null || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*(\\?.*)?")) {
            throw new IllegalStateException("TEST_DATABASE_URL must name a disposable local PostgreSQL test database");
        }
        properties.add("spring.datasource.url", () -> url);
    }

    @Autowired Flyway flyway;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;

    @Test
    void migrationCompletesBeforeJpaValidationAndApplicationBecomesUsable() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version='1' and success", Integer.class)).isEqualTo(1);
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("scheduling.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("send.mail")).isEqualTo("false");
        assertThat(environment.getProperty("discord.webhook.execute")).isEqualTo("false");
    }
}
