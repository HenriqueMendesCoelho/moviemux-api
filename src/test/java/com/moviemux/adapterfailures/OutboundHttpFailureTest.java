package com.moviemux.adapterfailures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.discord.adapter.repository.rest.impl.DiscordRepositoryImpl;
import com.moviemux.discord.domain.exception.WebhookFailure;
import com.moviemux.discord.domain.model.WebhookDelivery;
import com.moviemux.discord.domain.model.WebhookMovie;
import com.moviemux.discord.domain.repository.WebhookMovieSource;
import com.moviemux.movie.adapter.repository.rest.impl.MovieSocketRestRespositoryImpl;
import com.moviemux.movie.domain.exception.NotificationFailure;
import com.moviemux.movie.domain.repository.MovieNotificationRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class OutboundHttpFailureTest {
  private static final UUID MOVIE_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
  private HttpServer server;
  private WebClient client;
  private volatile int responseStatus = 200;
  private volatile String responseBody = "{\"id\":\"existing-message\"}";

  private record Request(String method, String uri) {}

  enum Operation {
    DISCORD_SEND("POST", "/?wait=true", WebhookFailure.class),
    DISCORD_EDIT("PATCH", "/messages/existing-message?wait=true", WebhookFailure.class),
    SOCKET_MOVIE(
        "POST", "/api/v1/movie/" + MOVIE_ID + "/note/emit/update", NotificationFailure.class),
    SOCKET_ALL("POST", "/api/v1/movie/all/emit/update", NotificationFailure.class);

    final String method;
    final String uri;
    final Class<? extends RuntimeException> failureType;

    Operation(String method, String uri, Class<? extends RuntimeException> failureType) {
      this.method = method;
      this.uri = uri;
      this.failureType = failureType;
    }
  }

  @BeforeEach
  void startLoopbackStub() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            exchange.getRequestBody().readAllBytes();
            requests.add(
                new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString()));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
          } finally {
            exchange.close();
          }
        });
    server.start();
    client = WebClient.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  @AfterEach
  void stopLoopbackStub() {
    if (server != null) server.stop(0);
  }

  static Stream<Arguments> remoteFailures() {
    return Stream.of(Operation.values())
        .flatMap(operation -> Stream.of(500, 503).map(status -> Arguments.of(operation, status)));
  }

  @ParameterizedTest
  @MethodSource("remoteFailures")
  void serverErrorsBecomeTypedFailuresWithOriginalStatusAndOneRequest(
      Operation operation, int status) throws Exception {
    responseStatus = status;
    responseBody = "{\"message\":\"synthetic downstream error\"}";

    assertThatThrownBy(() -> invoke(operation))
        .isInstanceOf(operation.failureType)
        .satisfies(
            failure -> {
              assertThat(failure.getCause()).isInstanceOf(WebClientResponseException.class);
              assertThat(((WebClientResponseException) failure.getCause()).getStatusCode().value())
                  .isEqualTo(status);
            });

    assertSingleRequest(operation);
  }

  static Stream<Arguments> invalidWebhookResponses() {
    return Stream.of(Operation.DISCORD_SEND, Operation.DISCORD_EDIT)
        .flatMap(
            operation ->
                Stream.of("", "not-json", "{\"id\":").map(body -> Arguments.of(operation, body)));
  }

  @ParameterizedTest
  @MethodSource("invalidWebhookResponses")
  void emptyOrMalformedWebhookResponseCannotBeReportedAsSentOrSkipped(
      Operation operation, String body) throws Exception {
    responseBody = body;

    assertThatThrownBy(() -> invoke(operation))
        .isInstanceOf(WebhookFailure.class)
        .hasCauseInstanceOf(
            body.isEmpty()
                ? NullPointerException.class
                : org.springframework.core.codec.DecodingException.class);

    assertSingleRequest(operation);
  }

  static Stream<Arguments> missingWebhookMessageIds() {
    return Stream.of(Operation.DISCORD_SEND, Operation.DISCORD_EDIT)
        .flatMap(
            operation ->
                Stream.of("{}", "{\"id\":null}", "{\"id\":\"\"}", "{\"id\":\" \\t\"}")
                    .map(body -> Arguments.of(operation, body)));
  }

  @ParameterizedTest
  @MethodSource("missingWebhookMessageIds")
  void structurallyInvalidWebhookResponseCannotBecomeSuccessfulDelivery(
      Operation operation, String body) throws Exception {
    responseBody = body;

    assertThatThrownBy(() -> invoke(operation))
        .isInstanceOf(WebhookFailure.class)
        .hasCauseInstanceOf(IllegalArgumentException.class)
        .hasMessage("External integration failed")
        .satisfies(
            failure -> assertThat(failure.getCause()).hasMessage("Missing webhook message id"));

    assertSingleRequest(operation);
  }

  @ParameterizedTest
  @EnumSource(
      value = Operation.class,
      names = {"SOCKET_MOVIE", "SOCKET_ALL"})
  void socketSuccessDoesNotRequireJsonBecauseItsResponseContractIsVoid(Operation operation)
      throws Exception {
    // This adapter intentionally ignores response content. Invalid JSON is not a socket failure.
    responseBody = "not-json";
    Object result = invoke(operation);
    if (operation == Operation.SOCKET_MOVIE) {
      assertThat(result).isEqualTo(MovieNotificationRepository.Delivery.SENT);
    }
    assertSingleRequest(operation);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void webhookControlFlagsAreIndependentAndDisabledOperationDoesNotReadPayload(boolean enableSend)
      throws Exception {
    var adapter = discord();
    ReflectionTestUtils.setField(adapter, "execute", enableSend);
    ReflectionTestUtils.setField(adapter, "update", !enableSend);
    var unreadable = mock(WebhookMovieSource.class);
    WebhookDelivery skipped = enableSend ? adapter.edit(unreadable) : adapter.send(unreadable);
    assertThat(skipped.status()).isEqualTo(WebhookDelivery.Status.DISABLED);
    assertThat(skipped.info()).isNull();
    verifyNoInteractions(unreadable);
    assertThat(requests).isEmpty();

    WebhookDelivery sent = enableSend ? adapter.send(source()) : adapter.edit(source());
    assertThat(sent.status()).isEqualTo(WebhookDelivery.Status.SENT);
    assertThat(sent.info().getMessageId()).isEqualTo("existing-message");
    assertSingleRequest(enableSend ? Operation.DISCORD_SEND : Operation.DISCORD_EDIT);
  }

  private Object invoke(Operation operation) {
    return switch (operation) {
      case DISCORD_SEND -> discord().send(source());
      case DISCORD_EDIT -> discord().edit(source());
      case SOCKET_MOVIE -> socket().notifyMovie(MOVIE_ID, "update", null, null);
      case SOCKET_ALL -> {
        socket().notifyAllMovies("update");
        yield null;
      }
    };
  }

  private void assertSingleRequest(Operation operation) throws InterruptedException {
    assertThat(requests.poll(3, TimeUnit.SECONDS))
        .isEqualTo(new Request(operation.method, operation.uri));
    assertThat(requests).as("no automatic second request after completion").isEmpty();
  }

  private DiscordRepositoryImpl discord() {
    var adapter = new DiscordRepositoryImpl();
    ReflectionTestUtils.setField(adapter, "webClientDiscord", client);
    ReflectionTestUtils.setField(adapter, "mapper", json);
    ReflectionTestUtils.setField(adapter, "execute", true);
    ReflectionTestUtils.setField(adapter, "update", true);
    return adapter;
  }

  private MovieSocketRestRespositoryImpl socket() {
    var adapter = new MovieSocketRestRespositoryImpl();
    ReflectionTestUtils.setField(adapter, "webClientSocketApi", client);
    ReflectionTestUtils.setField(adapter, "mapper", json);
    return adapter;
  }

  private WebhookMovieSource source() {
    var snapshot =
        new WebhookMovie(
            MOVIE_ID,
            "Synthetic movie",
            null,
            true,
            "Synthetic viewer",
            null,
            "existing-message",
            List.of());
    return new WebhookMovieSource() {
      public boolean showNotes() {
        return true;
      }

      public String messageId() {
        return "existing-message";
      }

      public WebhookMovie snapshot() {
        return snapshot;
      }
    };
  }
}
