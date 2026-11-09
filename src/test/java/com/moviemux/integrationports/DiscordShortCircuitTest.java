package com.moviemux.integrationports;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.application.spring.bridge.DiscordMovieBridge;
import com.moviemux.discord.adapter.repository.rest.impl.DiscordRepositoryImpl;
import com.moviemux.discord.domain.model.WebhookDelivery;
import com.moviemux.discord.domain.repository.WebhookMovieSource;
import com.moviemux.discord.domain.usecase.impl.SendMessageWebhookUseCaseImpl;
import com.moviemux.discord.domain.usecase.impl.UpdateMessageWebhookUseCaseImpl;
import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity;
import com.moviemux.movie.adapter.repository.postgres.entity.MovieDiscordEntity;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DiscordShortCircuitTest {
  private final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000042");
  private final MovieRepository movies = mock(MovieRepository.class);
  private final DiscordRepositoryImpl webhook = new DiscordRepositoryImpl();

  private MovieEntity unreadableRelationships() {
    var movie = mock(MovieEntity.class);
    when(movie.getNotes()).thenThrow(new IllegalStateException("ratings must stay unloaded"));
    when(movie.getUser()).thenThrow(new IllegalStateException("owner must stay unloaded"));
    when(movies.findById(id)).thenReturn(Optional.of(movie));
    return movie;
  }

  private void assertRelationshipsUnread(MovieEntity movie) {
    verify(movie, never()).getNotes();
    verify(movie, never()).getUser();
    verify(movies, never()).saveAndFlush(any(MovieEntity.class));
    verify(movies, times(1)).findById(id);
  }

  @Test
  void disabledSendThroughUsecaseDoesNotMaterializeSnapshotOrReadMetadata() {
    var movie = unreadableRelationships();
    new SendMessageWebhookUseCaseImpl(
            webhook,
            new DiscordMovieBridge(
                new com.moviemux.movie.adapter.repository.postgres.MovieWebhookAccessAdapter(
                    movies)))
        .execute(id);
    assertRelationshipsUnread(movie);
    verify(movie, never()).isShowNotes();
    verify(movie, never()).getMovieDiscord();
    verify(movie, never()).getPortugueseTitle();
  }

  @Test
  void hiddenMovieUpdateDoesNotReadReferenceOrRelationshipsEvenWhenAdapterEnabled() {
    var movie = unreadableRelationships();
    when(movie.isShowNotes()).thenReturn(false);
    ReflectionTestUtils.setField(webhook, "update", true);
    new UpdateMessageWebhookUseCaseImpl(
            webhook,
            new DiscordMovieBridge(
                new com.moviemux.movie.adapter.repository.postgres.MovieWebhookAccessAdapter(
                    movies)))
        .execute(id);
    assertRelationshipsUnread(movie);
    verify(movie, never()).getMovieDiscord();
  }

  @Test
  void disabledUpdateDoesNotReadReferenceOrRelationshipsAfterVisibilityCheck() {
    var movie = unreadableRelationships();
    when(movie.isShowNotes()).thenReturn(true);
    new UpdateMessageWebhookUseCaseImpl(
            webhook,
            new DiscordMovieBridge(
                new com.moviemux.movie.adapter.repository.postgres.MovieWebhookAccessAdapter(
                    movies)))
        .execute(id);
    assertRelationshipsUnread(movie);
    verify(movie, never()).getMovieDiscord();
  }

  @Test
  void missingMessageReferenceSkipsSnapshotAndItsUnloadedRelationships() {
    var movie = unreadableRelationships();
    when(movie.isShowNotes()).thenReturn(true);
    when(movie.getMovieDiscord()).thenReturn(null);
    ReflectionTestUtils.setField(webhook, "update", true);
    new UpdateMessageWebhookUseCaseImpl(
            webhook,
            new DiscordMovieBridge(
                new com.moviemux.movie.adapter.repository.postgres.MovieWebhookAccessAdapter(
                    movies)))
        .execute(id);
    assertRelationshipsUnread(movie);
    verify(movie, never()).getPortugueseTitle();
  }

  @Test
  void blankMessageReferenceSkipsSnapshotAndItsUnloadedRelationships() {
    var movie = unreadableRelationships();
    when(movie.isShowNotes()).thenReturn(true);
    when(movie.getMovieDiscord()).thenReturn(new MovieDiscordEntity(null, " ", movie));
    ReflectionTestUtils.setField(webhook, "update", true);
    new UpdateMessageWebhookUseCaseImpl(
            webhook,
            new DiscordMovieBridge(
                new com.moviemux.movie.adapter.repository.postgres.MovieWebhookAccessAdapter(
                    movies)))
        .execute(id);
    assertRelationshipsUnread(movie);
    verify(movie, never()).getPortugueseTitle();
  }

  @Test
  void enabledSendDoesNotDereferenceExistingMessageReference() {
    var movie = spy(MovieEntity.builder().id(id).portugueseTitle("Movie").build());
    doThrow(new IllegalStateException("reference must remain unloaded on create"))
        .when(movie)
        .getMovieDiscord();
    when(movies.findById(id)).thenReturn(Optional.of(movie));
    var client =
        org.springframework.web.reactive.function.client.WebClient.builder()
            .exchangeFunction(
                request ->
                    reactor.core.publisher.Mono.just(
                        org.springframework.web.reactive.function.client.ClientResponse.create(
                                org.springframework.http.HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body("{\"id\":\"remote-message\"}")
                            .build()))
            .build();
    ReflectionTestUtils.setField(webhook, "execute", true);
    ReflectionTestUtils.setField(
        webhook,
        "mapper",
        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    ReflectionTestUtils.setField(webhook, "webClientDiscord", client);
    new SendMessageWebhookUseCaseImpl(
            webhook,
            new DiscordMovieBridge(
                new com.moviemux.movie.adapter.repository.postgres.MovieWebhookAccessAdapter(
                    movies)))
        .execute(id);
    verify(movie, never()).getMovieDiscord();
    verify(movies).saveAndFlush(same(movie));
  }

  @Test
  void disabledAdapterNeverDereferencesIndependentSource() {
    var source = mock(WebhookMovieSource.class);
    when(source.snapshot()).thenThrow(new IllegalStateException("snapshot must remain lazy"));
    when(source.messageId()).thenThrow(new IllegalStateException("metadata must remain lazy"));
    assertThat(webhook.send(source).status()).isEqualTo(WebhookDelivery.Status.DISABLED);
    assertThat(webhook.edit(source).status()).isEqualTo(WebhookDelivery.Status.DISABLED);
    verifyNoInteractions(source);
  }
}
