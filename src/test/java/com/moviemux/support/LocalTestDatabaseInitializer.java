package com.moviemux.support;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** Reject nonlocal databases before any Spring test datasource can be initialized. */
public final class LocalTestDatabaseInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        requireLocalTestDatabase(context.getEnvironment().getProperty("spring.datasource.url"));
    }

    static void requireLocalTestDatabase(String url) {
        if (url == null || !url.matches(
                "jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*")) {
            throw new IllegalStateException("Tests require an explicit loopback PostgreSQL test database without URL overrides");
        }
    }
}
