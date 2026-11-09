package com.moviemux.characterization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.discord.adapter.repository.rest.impl.DiscordRepositoryImpl;
import com.moviemux.kronusintegrationtool.adapter.repository.rest.impl.KronusIntegrationToolRepositoryImpl;
import com.moviemux.movie.adapter.repository.rest.impl.MovieSocketRestRespositoryImpl;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import com.moviemux.kronusintegrationtool.domain.exception.IntegrationFailure;
import com.moviemux.movie.domain.exception.NotificationFailure;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class ExternalHttpContractTest {
    private HttpServer server;
    private WebClient client;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{}";
    record Request(String method, String uri, String body) {}

    @BeforeEach void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = WebClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE).build();
    }
    @AfterEach void stopStub() { server.stop(0); }
    private Request received() throws Exception {
        Request result = requests.poll(3, TimeUnit.SECONDS);
        assertThat(result).as("request reached local HTTP stub").isNotNull();
        return result;
    }
    private KronusIntegrationToolRepositoryImpl kit() {
        var repository = new KronusIntegrationToolRepositoryImpl();
        ReflectionTestUtils.setField(repository, "webClientKit", client);
        ReflectionTestUtils.setField(repository, "mapper", mapper);
        return repository;
    }
    private MovieSocketRestRespositoryImpl socket() {
        var repository = new MovieSocketRestRespositoryImpl();
        ReflectionTestUtils.setField(repository, "webClientSocketApi", client);
        ReflectionTestUtils.setField(repository, "mapper", mapper);
        return repository;
    }
    @Test void watchProvidersPreservesCountryAndNullableOfferLists() throws Exception {
        responseBody = """
                {"id":42,"results":{"BR":{"link":"https://example.test/watch","flatrate":[
                {"logoPath":"/logo.png","providerId":7,"providerName":"Provider","displayPriority":2}]}}}
                """;
        var result = kit().getWatchProviders(42L);
        assertThat(result.getId()).isEqualTo(42L);
        var brazil = result.getResults().get("BR");
        assertThat(brazil.getLink()).isEqualTo("https://example.test/watch");
        assertThat(brazil.getRent()).isNull();
        assertThat(brazil.getBuy()).isNull();
        assertThat(brazil.getFlatrate()).singleElement().satisfies(provider -> {
            assertThat(provider.getProviderId()).isEqualTo(7L);
            assertThat(provider.getProviderName()).isEqualTo("Provider");
            assertThat(provider.getLogoPath()).isEqualTo("/logo.png");
            assertThat(provider.getDisplayPriority()).isEqualTo(2);
        });
        assertThat(received()).isEqualTo(new Request("GET", "/api/v1/tmdb/movie/42/watch/providers", ""));
    }
    @ParameterizedTest @ValueSource(ints = {404, 500, 503})
    void kitHttpErrorsAreNotSuccessfulEmptyResults(int status) {
        responseStatus = status;
        assertThatThrownBy(() -> kit().getWatchProviders(42L)).isInstanceOf(IntegrationFailure.class);
    }
    @Test void malformedProviderBodyFailsRatherThanReturningEmptyCatalog() {
        responseBody = "not-json";
        assertThatThrownBy(() -> kit().getWatchProviders(42L)).isInstanceOf(IntegrationFailure.class);
    }
    @Test void searchPreservesQueryAndPagingParameters() throws Exception {
        responseBody = "{\"page\":2,\"results\":[],\"totalPages\":3,\"totalResults\":5}";
        kit().searchByName("The Movie", 2, "pt-Br", false);
        assertThat(received().uri()).isEqualTo("/api/v1/tmdb/search/movie?query=The%20Movie&page=2&language=pt-Br&include_adult=false");
    }
    @Test void genresMapIdsAndNames() throws Exception {
        responseBody = "{\"genres\":[{\"id\":18,\"name\":\"Drama\"}]}";
        assertThat(kit().listGenres()).singleElement().satisfies(genre -> {
            assertThat(genre.getId()).isEqualTo(18L);
            assertThat(genre.getName()).isEqualTo("Drama");
        });
        assertThat(received().uri()).isEqualTo("/api/v1/tmdb/genre/movie/list?language=pt-Br");
    }
    @Test void disabledMailDoesNotSendEvenWithMissingPayload() {
        kit().sendMailTemplate(null);
        assertThat(requests).isEmpty();
    }
    @Test void socketAllMoviesDefaultsBlankEventToUpdate() throws Exception {
        socket().emitAllMoviesEvent(" ");
        var request = received();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri()).isEqualTo("/api/v1/movie/all/emit/update");
        assertThat(mapper.readTree(request.body())).isEqualTo(mapper.readTree("{\"event\":\"update\"}"));
    }
    @Test void socketKeepsExistingEmmitedByUserIdSpellingAndOmitsAbsentContent() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000042");
        socket().emitEventMovie(id, "delete", null, "actor");
        var request = received();
        assertThat(request.uri()).isEqualTo("/api/v1/movie/" + id + "/note/emit/delete");
        JsonNode body = mapper.readTree(request.body());
        assertThat(body.get("movie").asText()).isEqualTo(id.toString());
        assertThat(body.get("event").asText()).isEqualTo("delete");
        assertThat(body.get("emmitedByUserId").asText()).isEqualTo("actor");
        assertThat(body.has("emittedByUserId")).isFalse();
        assertThat(body.has("content")).isFalse();
    }
    @Test void socketSkipsInvalidEventOrMovieId() {
        socket().emitEventMovie(null, "update", null, null);
        socket().emitEventMovie(UUID.randomUUID(), " ", null, null);
        assertThat(requests).isEmpty();
    }
    @Test void socketRemoteFailureIsObservable() {
        responseStatus = 500;
        assertThatThrownBy(() -> socket().emitAllMoviesEvent("update")).isInstanceOf(NotificationFailure.class);
    }
    @Test void disabledDiscordDoesNotConstructOrSendPayload() {
        var repository = new DiscordRepositoryImpl();
        ReflectionTestUtils.setField(repository, "webClientDiscord", client);
        assertThat(repository.execute(null)).isNull();
        assertThat(repository.update(null)).isNull();
        assertThat(requests).isEmpty();
    }
    private DiscordRepositoryImpl discord() {
        var repository = new DiscordRepositoryImpl();
        ReflectionTestUtils.setField(repository, "webClientDiscord", client);
        ReflectionTestUtils.setField(repository, "mapper", mapper);
        ReflectionTestUtils.setField(repository, "execute", true);
        ReflectionTestUtils.setField(repository, "update", true);
        ReflectionTestUtils.setField(repository, "roleId", "synthetic-role");
        return repository;
    }
    private com.moviemux.movie.domain.Movie movie() {
        return com.moviemux.movie.domain.Movie.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000042"))
                .portugueseTitle("Synthetic movie").showNotes(false).build();
    }
    @Test void discordCreatePostsWebhookAndMapsRemoteMessageId() throws Exception {
        responseBody = "{\"id\":\"synthetic-message\"}";
        assertThat(discord().execute(movie()).getMessageId()).isEqualTo("synthetic-message");
        var request = received();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri()).isEqualTo("/?wait=true");
        var body = mapper.readTree(request.body());
        assertThat(body.get("content").asText()).isEqualTo("<@&synthetic-role>");
        assertThat(body.get("embeds").get(0).get("title").asText()).isEqualTo("Synthetic movie");
        assertThat(body.get("embeds").get(0).get("fields").isEmpty()).isTrue();
    }
    @Test void discordUpdateWithoutMessageReferenceDoesNotSend() {
        assertThat(discord().update(movie())).isNull();
        assertThat(requests).isEmpty();
    }
    @Test void enabledEmailUsesSnakeCaseTemplateIdAndDoesNotExposeInternalFlags() throws Exception {
        var repository = kit();
        ReflectionTestUtils.setField(repository, "sendMail", true);
        repository.sendMailTemplate(com.moviemux.kronusintegrationtool.domain.SendMailTemplate.builder()
                .from("sender@example.test").to("recipient@example.test").templateId("synthetic-template")
                .subject("Synthetic subject").ignoreNotifyPreferences(true).build());
        var request = received();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.uri()).isEqualTo("/api/v1/sendgrid/template");
        var body = mapper.readTree(request.body());
        assertThat(body.get("template_id").asText()).isEqualTo("synthetic-template");
        assertThat(body.get("to").asText()).isEqualTo("recipient@example.test");
        assertThat(body.has("ignoreNotifyPreferences")).isFalse();
        assertThat(body.has("ignore_notify_preferences")).isFalse();
    }

}
