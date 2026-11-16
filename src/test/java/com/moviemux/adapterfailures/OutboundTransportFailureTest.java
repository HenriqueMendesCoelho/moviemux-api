package com.moviemux.adapterfailures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.moviemux.discord.adapter.repository.rest.impl.DiscordRepositoryImpl;
import com.moviemux.discord.domain.exception.WebhookFailure;
import com.moviemux.discord.domain.model.WebhookMovie;
import com.moviemux.discord.domain.repository.WebhookMovieSource;
import com.moviemux.movie.adapter.repository.rest.impl.MovieSocketRestRespositoryImpl;
import com.moviemux.movie.domain.exception.NotificationFailure;
import java.util.List;
import java.util.UUID;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.web.client.RestClient;

class OutboundTransportFailureTest {
  private static final UUID MOVIE_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

  enum Operation {
    DISCORD_SEND,
    DISCORD_EDIT,
    SOCKET_MOVIE,
    SOCKET_ALL
  }

  @ParameterizedTest
  @EnumSource(Operation.class)
  void timeoutRemainsTechnicalWithoutAutomaticRetryOrSuccessfulDelivery(Operation operation) {
    // Injected exchange failure verifies translation, not real TCP timeout configuration or timing.
    var timeout = new SocketTimeoutException("synthetic downstream timeout");
    var calls = new AtomicInteger();
    var client =
        RestClient.builder()
            .baseUrl("http://127.0.0.1:1")
            .requestFactory((uri, method) -> {
              calls.incrementAndGet();
              throw timeout;
            })
            .build();
    var webhook = new DiscordRepositoryImpl(client, true, true, "synthetic-role");
    var socket = new MovieSocketRestRespositoryImpl(client);
    Class<? extends RuntimeException> expected =
        operation == Operation.DISCORD_SEND || operation == Operation.DISCORD_EDIT
            ? WebhookFailure.class
            : NotificationFailure.class;

    assertThatThrownBy(
            () -> {
              switch (operation) {
                case DISCORD_SEND -> webhook.send(source());
                case DISCORD_EDIT -> webhook.edit(source());
                case SOCKET_MOVIE -> socket.notifyMovie(MOVIE_ID, "update", null, null);
                case SOCKET_ALL -> socket.notifyAllMovies("update");
              }
            })
        .isInstanceOf(expected)
        .hasRootCause(timeout);

    assertThat(calls).as("no implicit timeout retry").hasValue(1);
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
