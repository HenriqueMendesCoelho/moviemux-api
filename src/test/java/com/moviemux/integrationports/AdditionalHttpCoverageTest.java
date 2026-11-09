package com.moviemux.integrationports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.discord.adapter.repository.rest.impl.DiscordRepositoryImpl;
import com.moviemux.discord.domain.model.WebhookDelivery;
import com.moviemux.discord.domain.model.WebhookMovie;
import com.moviemux.discord.domain.repository.WebhookMovieSource;
import com.moviemux.kronusintegrationtool.adapter.repository.rest.impl.KronusIntegrationToolRepositoryImpl;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AdditionalHttpCoverageTest {
    private HttpServer server;
    private WebClient client;
    private volatile String response = "{}";
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private record Request(String method, String path, String body) {}

    @BeforeEach
    void startLocalStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = WebClient.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopStub() { server.stop(0); }

    private Request request() throws InterruptedException {
        Request request = requests.poll(3, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        return request;
    }

    private KronusIntegrationToolRepositoryImpl catalog() {
        var adapter = new KronusIntegrationToolRepositoryImpl();
        ReflectionTestUtils.setField(adapter, "webClientKit", client);
        return adapter;
    }

    @Test
    void summaryPreservesRemoteSnakeCaseAndZeroRuntime() throws Exception {
        response = """
                {"tmdb_id":42,"portuguese_title":"Filme","english_title":"Movie",
                 "runtime":0,"genres":["Drama"],"release_date":"2020-01-02"}
                """;
        var result = catalog().movieSummary(42L);
        assertThat(result.getTmdbId()).isEqualTo(42L);
        assertThat(result.getPortugueseTitle()).isEqualTo("Filme");
        assertThat(result.getEnglishTitle()).isEqualTo("Movie");
        assertThat(result.getRuntime()).isZero();
        assertThat(result.getGenres()).containsExactly("Drama");
        assertThat(result.getReleaseDate()).hasToString("2020-01-02");
        assertThat(request().path()).isEqualTo("/api/v1/tmdb/movie/42/summary");
    }

    @Test
    void creditsKeepEmptyCastAndCrewDistinctFromMissingResponse() throws Exception {
        response = "{\"cast\":[],\"crew\":[]}";
        var result = catalog().getCredits(42L);
        assertThat(result.getCast()).isEmpty();
        assertThat(result.getCrew()).isEmpty();
        assertThat(request().path()).isEqualTo("/api/v1/tmdb/movie/42/credits");
    }

    @Test
    void webhookUpdateUsesPatchAndExistingReferenceWithSortedRatingFields() throws Exception {
        response = "{\"id\":\"existing-message\"}";
        var adapter = new DiscordRepositoryImpl();
        ReflectionTestUtils.setField(adapter, "webClientDiscord", client);
        ReflectionTestUtils.setField(adapter, "mapper", json);
        ReflectionTestUtils.setField(adapter, "update", true);
        var movie = new WebhookMovie(UUID.fromString("00000000-0000-0000-0000-000000000042"),
                "Synthetic movie", null, true, "Owner", OffsetDateTime.parse("2020-01-02T03:04:05Z"),
                "existing-message", List.of(new WebhookMovie.Rating("Alice", 8)));
        var source = new WebhookMovieSource() {
            public boolean showNotes() { return true; }
            public String messageId() { return "existing-message"; }
            public WebhookMovie snapshot() { return movie; }
        };
        var delivery = adapter.edit(source);
        assertThat(delivery.status()).isEqualTo(WebhookDelivery.Status.SENT);
        assertThat(delivery.info().getMessageId()).isEqualTo("existing-message");
        var sent = request();
        assertThat(sent.method()).isEqualTo("PATCH");
        assertThat(sent.path()).isEqualTo("/messages/existing-message?wait=true");
        var body = json.readTree(sent.body());
        assertThat(body.get("embeds").get(0).get("title").asText()).isEqualTo("Synthetic movie | Nota: 8.0");
        assertThat(body.get("embeds").get(0).get("fields").get(0).get("name").asText()).isEqualTo("Alice");
        assertThat(requests).isEmpty();
    }
}
