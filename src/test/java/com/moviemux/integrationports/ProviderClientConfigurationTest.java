package com.moviemux.integrationports;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.discord.adapter.repository.rest.impl.DiscordRepositoryImpl;
import com.moviemux.discord.application.spring.configuration.DiscordRestClientConfig;
import com.moviemux.discord.domain.model.WebhookMovie;
import com.moviemux.discord.domain.repository.WebhookMovieSource;
import com.moviemux.kronusintegrationtool.adapter.repository.rest.impl.KronusIntegrationToolRepositoryImpl;
import com.moviemux.kronusintegrationtool.application.spring.configuration.KronusIntegrationToolRestClientConfig;
import com.moviemux.movie.adapter.repository.rest.impl.MovieSocketRestRespositoryImpl;
import com.moviemux.movie.application.spring.configuration.SocketApiRestClientConfig;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class ProviderClientConfigurationTest {
  private record Request(String method, String uri, String authorization, String contentType) {}

  @Test
  void qualifiedClientsKeepProviderCredentialsAndJsonConfigurationSeparate() throws Exception {
    var requests = new LinkedBlockingQueue<Request>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      try {
        exchange.getRequestBody().readAllBytes();
        requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
            exchange.getRequestHeaders().getFirst("Authorization"),
            exchange.getRequestHeaders().getFirst("Content-Type")));
        String json = exchange.getRequestURI().getPath().contains("tmdb")
            ? "{\"id\":42,\"results\":{}}" : "{\"id\":\"discord-message\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
      } finally {
        exchange.close();
      }
    });
    server.start();
    try (var context = new AnnotationConfigApplicationContext()) {
      String url = "http://127.0.0.1:" + server.getAddress().getPort();
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic", Map.of(
          "kit.url", url, "kit.key", "synthetic-kit-key",
          "socket.io.api.url", url, "socket.io.api.key", "synthetic-socket-key",
          "discord.webhook.url", url + "/api/webhooks/synthetic-id/synthetic-token", "discord.webhook.role.id", "role",
          "discord.webhook.execute", "true", "discord.webhook.update", "true")));
      context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
      context.register(KronusIntegrationToolRestClientConfig.class, DiscordRestClientConfig.class,
          SocketApiRestClientConfig.class, KronusIntegrationToolRepositoryImpl.class,
          DiscordRepositoryImpl.class, MovieSocketRestRespositoryImpl.class);
      context.refresh();

      assertThat(context.getBean(KronusIntegrationToolRepositoryImpl.class).getWatchProviders(42L).getId())
          .isEqualTo(42L);
      assertThat(requests.poll(3, TimeUnit.SECONDS)).isEqualTo(new Request(
          "GET", "/api/v1/tmdb/movie/42/watch/providers", "Bearer synthetic-kit-key", "application/json"));

      var movie = new WebhookMovie(UUID.randomUUID(), "Synthetic movie", null, false, "Viewer",
          java.time.OffsetDateTime.parse("2026-01-02T03:04:05Z"), "existing-message", List.of());
      var source = new WebhookMovieSource() {
        public boolean showNotes() { return false; }
        public String messageId() { return "existing-message"; }
        public WebhookMovie snapshot() { return movie; }
      };
      assertThat(context.getBean(DiscordRepositoryImpl.class).send(source).info().getMessageId())
          .isEqualTo("discord-message");
      assertThat(requests.poll(3, TimeUnit.SECONDS)).isEqualTo(new Request(
          "POST", "/api/webhooks/synthetic-id/synthetic-token?wait=true", null, "application/json"));
      assertThat(context.getBean(DiscordRepositoryImpl.class).edit(source).info().getMessageId())
          .isEqualTo("discord-message");
      assertThat(requests.poll(3, TimeUnit.SECONDS)).isEqualTo(new Request(
          "PATCH", "/api/webhooks/synthetic-id/synthetic-token/messages/existing-message?wait=true", null, "application/json"));

      context.getBean(MovieSocketRestRespositoryImpl.class).notifyAllMovies("update");
      assertThat(requests.poll(3, TimeUnit.SECONDS)).isEqualTo(new Request(
          "POST", "/api/v1/movie/all/emit/update", "Bearer synthetic-socket-key", "application/json"));
      assertThat(requests).isEmpty();
    } finally {
      server.stop(0);
    }
  }
}
