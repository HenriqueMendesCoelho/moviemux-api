package com.moviemux.migration;

import com.moviemux.MovieMuxApplication;
import com.moviemux.movie.application.spring.MovieGenreScheduler;
import com.moviemux.movie.domain.usecase.CreateMovieGenreUseCase;
import com.moviemux.wishlist.application.spring.WishlistCleanupJob;
import com.moviemux.wishlist.domain.usecase.DeleteMovieWishlistUseCase;
import com.sun.net.httpserver.HttpServer;
import org.apache.catalina.Lifecycle;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** Real Spring/Tomcat startup with scheduling enabled; all remote destinations are local stubs. */
class MigrationStartupGateIT {
    @TempDir Path temporary;
    private String url;
    private String user;
    private String password;
    private String schema;
    private HttpServer externalStub;
    private final AtomicInteger externalRequests = new AtomicInteger();
    private final Probe probe = new Probe();

    @BeforeEach
    void createDisposableSchemaAndLocalExternalStub() throws Exception {
        url = System.getenv("TEST_DATABASE_URL");
        assertThat(url).as("Disposable loopback PostgreSQL database required")
                .matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*");
        user = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
        password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
        schema = "startup_gate_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password)) {
            connection.createStatement().execute("create schema " + schema);
        }
        externalStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        externalStub.createContext("/", exchange -> {
            externalRequests.incrementAndGet();
            byte[] body = "{\"genres\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        externalStub.start();
    }

    @AfterEach
    void removeOnlyThisTestSchemaAndStopStub() throws Exception {
        if (externalStub != null) externalStub.stop(0);
        if (schema != null) try (var connection = DriverManager.getConnection(url, user, password)) {
            connection.createStatement().execute("drop schema " + schema + " cascade");
        }
    }

    @Test
    void appliedChecksumMismatchBlocksReadinessConnectorAndBothEnabledJobs() throws Exception {
        flyway().migrate();
        String v1;
        try (var input = getClass().getResourceAsStream("/db/migration/V1__initial_schema.sql")) {
            assertThat(input).isNotNull();
            v1 = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.writeString(temporary.resolve("V1__initial_schema.sql"), v1.replace("varchar(100)", "varchar(101)"));
        assertThat(v1).contains("varchar(100)");
        assertStartupBlocked("checksum mismatch", "--spring.flyway.locations=filesystem:" + temporary);
        assertThat(flyway().info().current().getVersion().getVersion()).isEqualTo("1");
    }

    @Test
    void invalidPendingDdlBlocksReadinessConnectorAndBothEnabledJobs() throws Exception {
        flyway().migrate();
        Files.writeString(temporary.resolve("V2__startup_gate_invalid_ddl.sql"),
                "create table startup_gate_rollback_probe(id integer);\ncreate table deliberately_invalid (;");
        assertStartupBlocked("syntax error", "--spring.flyway.locations=classpath:db/migration,filesystem:" + temporary);
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement();
             var result = statement.executeQuery("select count(*) from information_schema.tables where table_schema='"
                     + schema + "' and table_name='startup_gate_rollback_probe'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isZero();
        }
        assertThat(flyway().info().current().getVersion().getVersion()).isEqualTo("1");
    }

    @Test
    void nonexistentTargetSchemaBlocksReadinessConnectorAndBothEnabledJobs() throws Exception {
        String absent = schema + "_absent";
        assertStartupBlocked("does not exist", "--spring.flyway.default-schema=" + absent,
                "--spring.flyway.schemas=" + absent, "--spring.jpa.properties.hibernate.default_schema=" + absent);
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement();
             var result = statement.executeQuery("select count(*) from information_schema.schemata where schema_name='" + absent + "'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isZero();
        }
    }

    @Test
    void successfulStartupActuallyStartsConnectorReadinessAndBothRealScheduledJobs() {
        try (ConfigurableApplicationContext context = application().run(arguments())) {
            assertThat(context.getEnvironment().getProperty("scheduling.enabled")).isEqualTo("true");
            assertThat(context.getBeansOfType(MovieGenreScheduler.class)).hasSize(1);
            assertThat(context.getBeansOfType(WishlistCleanupJob.class)).hasSize(1);
            assertThat(probe.ready.get()).isEqualTo(1);
            assertThat(probe.acceptingTraffic.get()).isEqualTo(1);
            assertThat(probe.connectorsStarted.get()).isEqualTo(1);
            assertThat(probe.webServersStarted.get()).isEqualTo(1);
            // Retain production 5s/10s initial delays: this is a positive control for real scheduling.
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(probe.genreInvocations.get()).isEqualTo(1);
                assertThat(probe.cleanupInvocations.get()).isEqualTo(1);
            });
            assertThat(externalRequests.get()).isZero();
        }
    }

    private void assertStartupBlocked(String expectedFailure, String... overrides) {
        Throwable failure = catchThrowable(() -> {
            try (var unexpected = application().run(arguments(overrides))) {
                fail("Startup unexpectedly succeeded");
            }
        });
        assertThat(failure).isNotNull().hasStackTraceContaining(expectedFailure);
        List<Throwable> causes = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) causes.add(cause);
        assertThat(causes).as("Failure must originate in Flyway, not unrelated wiring or test configuration")
                .anyMatch(FlywayException.class::isInstance);
        assertThat(probe.ready.get()).isZero();
        assertThat(probe.acceptingTraffic.get()).isZero();
        assertThat(probe.connectorsStarted.get()).as("Tomcat connector never entered started state").isZero();
        assertThat(probe.webServersStarted.get()).isZero();
        assertThat(probe.genreInvocations.get()).isZero();
        assertThat(probe.cleanupInvocations.get()).isZero();
        assertThat(externalRequests.get()).isZero();
    }

    private SpringApplication application() {
        SpringApplication application = new SpringApplication(MovieMuxApplication.class, JobProbeConfiguration.class);
        application.addInitializers(context -> context.getBeanFactory().registerSingleton("startupGateProbe", probe));
        application.addListeners((ApplicationEvent event) -> {
            if (event instanceof org.springframework.boot.context.event.ApplicationReadyEvent) probe.ready.incrementAndGet();
            if (event instanceof ServletWebServerInitializedEvent) probe.webServersStarted.incrementAndGet();
            if (event instanceof AvailabilityChangeEvent<?> availability
                    && availability.getState() == ReadinessState.ACCEPTING_TRAFFIC) probe.acceptingTraffic.incrementAndGet();
        });
        return application;
    }

    private String[] arguments(String... overrides) {
        String stub = "http://127.0.0.1:" + externalStub.getAddress().getPort();
        List<String> arguments = new ArrayList<>(List.of("--spring.profiles.active=test", "--server.address=127.0.0.1",
                "--server.port=0", "--scheduling.enabled=true", "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + user, "--spring.datasource.password=" + password,
                "--spring.flyway.default-schema=" + schema, "--spring.flyway.schemas=" + schema,
                "--spring.jpa.properties.hibernate.default_schema=" + schema, "--kit.url=" + stub,
                "--socket.io.api.url=" + stub, "--discord.webhook.url=" + stub));
        // Spring command line duplicate values are not last-wins. Replace exact keys deliberately.
        for (String override : overrides) {
            String key = override.substring(0, override.indexOf('=') + 1);
            arguments.removeIf(argument -> argument.startsWith(key));
            arguments.add(override);
        }
        return arguments.toArray(String[]::new);
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                .createSchemas(false).baselineOnMigrate(false).cleanDisabled(true).locations("classpath:db/migration").load();
    }

    static final class Probe {
        final AtomicInteger ready = new AtomicInteger();
        final AtomicInteger acceptingTraffic = new AtomicInteger();
        final AtomicInteger connectorsStarted = new AtomicInteger();
        final AtomicInteger webServersStarted = new AtomicInteger();
        final AtomicInteger genreInvocations = new AtomicInteger();
        final AtomicInteger cleanupInvocations = new AtomicInteger();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JobProbeConfiguration {
        @Bean @Primary
        CreateMovieGenreUseCase scheduledGenreProbe(Probe probe) { return probe.genreInvocations::incrementAndGet; }

        @Bean @Primary
        DeleteMovieWishlistUseCase scheduledCleanupProbe(Probe probe) { return probe.cleanupInvocations::incrementAndGet; }

        @Bean
        WebServerFactoryCustomizer<TomcatServletWebServerFactory> connectorLifecycleProbe(Probe probe) {
            return factory -> factory.addConnectorCustomizers(connector -> connector.addLifecycleListener(event -> {
                if (Lifecycle.AFTER_START_EVENT.equals(event.getType())) probe.connectorsStarted.incrementAndGet();
            }));
        }
    }
}
