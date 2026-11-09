package com.moviemux.mailcontracts;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.mail.adapter.repository.rest.MovieMailRestRepository;
import com.moviemux.mail.application.spring.configuration.MailRestClientConfig;
import com.moviemux.mail.domain.exception.MailDeliveryFailure;
import com.moviemux.mail.domain.exception.MailDeliveryFailure.Reason;
import com.moviemux.mail.domain.model.SendMailTemplate;
import com.moviemux.mail.domain.repository.EmailGateway.Delivery;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class MailProviderContractTest {
  private final ObjectMapper json = new ObjectMapper();
  private final List<Request> requests = new CopyOnWriteArrayList<>();
  private final AtomicInteger status = new AtomicInteger(204);
  private HttpServer server;
  private MovieMailRestRepository gateway;

  @BeforeEach
  void startOnlyLocalProvider() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
          exchange.getRequestHeaders().getFirst("Content-Type"),
          exchange.getRequestHeaders().getFirst("Authorization"),
          json.readTree(exchange.getRequestBody())));
      byte[] body = "synthetic-private-response-and-reset-key".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(), status.get() == 204 ? -1 : body.length);
      if (status.get() != 204) exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    gateway = new MovieMailRestRepository(new MailRestClientConfig().restClientMail(json,
        "http://127.0.0.1:" + server.getAddress().getPort()), true);
  }

  @AfterEach
  void stopLocalProvider() {
    if (server != null) server.stop(0);
  }

  @Test
  void allDevelopTemplatesUseDedicatedEndpointExactMetadataAndNoKitCredentials() {
    List<SendMailTemplate> commands = List.of(
        SendMailTemplate.welcomeMail("synthetic@example.test", "Synthetic"),
        SendMailTemplate.forgotPasswordMail("synthetic@example.test", "Synthetic", "synthetic-key"),
        SendMailTemplate.passwordUpdatedMail("synthetic@example.test", "Synthetic"),
        SendMailTemplate.accountBlockedMail("synthetic@example.test", "Synthetic"));
    for (var command : commands) assertThat(gateway.sendEmail(command)).isEqualTo(Delivery.SENT);
    assertThat(requests).hasSize(4);
    assertThat(requests).extracting(request -> request.body().get("template_id").asText())
        .containsExactly("welcome", "reset_password", "password_updated", "account_blocked");
    for (int i = 0; i < commands.size(); i++) {
      Request request = requests.get(i);
      assertThat(request.method()).isEqualTo("POST");
      assertThat(request.path()).isEqualTo("/api/mail/send");
      assertThat(request.contentType()).isEqualTo("application/json");
      assertThat(request.authorization()).isNull();
      var fields = new ArrayList<String>();
      request.body().fieldNames().forEachRemaining(fields::add);
      assertThat(fields).containsExactlyInAnyOrder("to", "template_id", "metadata");
      assertThat(request.body().get("to").asText()).isEqualTo("synthetic@example.test");
      assertThat(request.body().get("metadata")).isEqualTo(json.valueToTree(commands.get(i).metadata()));
    }
    assertThat(requests.get(1).body().get("metadata").get("reset_link").asText())
        .isEqualTo("https://www.moviemux.com/password/change/synthetic-key");
    assertThat(requests.get(0).body().get("metadata").size()).isEqualTo(1);
    assertThat(requests.get(1).body().get("metadata").size()).isEqualTo(2);
  }

  @Test
  void disabledMailDoesNotReadCommandOrContactProvider() {
    var disabled = new MovieMailRestRepository(null, false);
    assertThat(disabled.sendEmail(null)).isEqualTo(Delivery.DISABLED);
    assertThat(requests).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 429, 500, 503})
  void providerErrorsAreSanitizedAndAttemptedOnlyOnce(int remoteStatus) {
    status.set(remoteStatus);
    assertThatThrownBy(() -> gateway.sendEmail(SendMailTemplate.welcomeMail(
        "synthetic@example.test", "Synthetic")))
        .isInstanceOfSatisfying(MailDeliveryFailure.class, failure -> {
          assertThat(failure.reason()).isEqualTo(Reason.HTTP_ERROR);
          assertThat(failure.getMessage()).isEqualTo("Mail provider request failed: HTTP_ERROR");
          assertThat(failure.getCause()).isNull();
        });
    assertThat(requests).hasSize(1);
  }

  @Test
  void transportAndClientFailuresDoNotExposeExceptionMessagesOrRetry() {
    AtomicInteger calls = new AtomicInteger();
    RestClient unavailable = RestClient.builder().requestFactory((uri, method) -> {
      calls.incrementAndGet();
      throw new IOException("synthetic-private-token");
    }).build();
    assertThatThrownBy(() -> new MovieMailRestRepository(unavailable, true)
        .sendEmail(SendMailTemplate.welcomeMail("synthetic@example.test", "Synthetic")))
        .isInstanceOfSatisfying(MailDeliveryFailure.class,
            failure -> assertThat(failure.reason()).isEqualTo(Reason.UNAVAILABLE));
    assertThat(calls).hasValue(1);

    RestClient invalid = RestClient.builder().requestFactory((uri, method) -> {
      calls.incrementAndGet();
      throw new RestClientException("synthetic-private-token");
    }).build();
    assertThatThrownBy(() -> new MovieMailRestRepository(invalid, true)
        .sendEmail(SendMailTemplate.welcomeMail("synthetic@example.test", "Synthetic")))
        .isInstanceOfSatisfying(MailDeliveryFailure.class,
            failure -> assertThat(failure.reason()).isEqualTo(Reason.INVALID_RESPONSE));
    assertThat(calls).hasValue(2);
  }

  @Test
  void publicMailCommandDefensivelyCopiesMetadataAndCannotBeChangedWhileQueued() {
    var source = new LinkedHashMap<>(Map.of("user", "Original"));
    var mail = new SendMailTemplate("synthetic@example.test", "welcome", source);
    source.put("user", "Changed");
    assertThat(mail.metadata()).containsExactlyEntriesOf(Map.of("user", "Original"));
    assertThatThrownBy(() -> mail.metadata().put("user", "Changed"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private record Request(String method, String path, String contentType, String authorization,
      JsonNode body) {}
}
