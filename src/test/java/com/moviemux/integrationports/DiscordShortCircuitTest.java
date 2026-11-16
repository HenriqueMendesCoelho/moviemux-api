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
import com.moviemux.movie.adapter.repository.postgres.entity.MovieDiscordEntity;
import com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiscordShortCircuitTest {
  private final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000042");
  private final MovieRepository movies = mock(MovieRepository.class);
  private DiscordRepositoryImpl webhook = new DiscordRepositoryImpl(null, false, false, "role");

  private org.springframework.transaction.support.TransactionTemplate transactions() {
    return new org.springframework.transaction.support.TransactionTemplate() {
      @Override
      public <T> T execute(
          org.springframework.transaction.support.TransactionCallback<T> callback) {
        return callback.doInTransaction(
            new org.springframework.transaction.support.SimpleTransactionStatus());
      }
    };
  }

  private MovieEntity unreadableRelationships() {
    var movie = mock(MovieEntity.class);
    when(movie.getNotes()).thenThrow(new IllegalStateException("ratings must stay unloaded"));
    when(movie.getUserId()).thenThrow(new IllegalStateException("owner must stay unloaded"));
    when(movies.findById(id)).thenReturn(Optional.of(movie));
    return movie;
  }

  private void assertRelationshipsUnread(MovieEntity movie) {
    verify(movie, never()).getNotes();
    verify(movie, never()).getUserId();
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
                    movies,
                    mock(com.moviemux.user.domain.repository.UserIdentityLookup.class),
                    transactions())))
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
    webhook = new DiscordRepositoryImpl(null, false, true, "role");
    new UpdateMessageWebhookUseCaseImpl(
            webhook,
            new DiscordMovieBridge(
                new com.moviemux.movie.adapter.repository.postgres.MovieWebhookAccessAdapter(
                    movies,
                    mock(com.moviemux.user.domain.repository.UserIdentityLookup.class),
                    transactions())))
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
                    movies,
                    mock(com.moviemux.user.domain.repository.UserIdentityLookup.class),
                    transactions())))
        .execute(id);
    assertRelationshipsUnread(movie);
    verify(movie, never()).getMovieDiscord();
  }

  @Test
  void missingMessageReferenceSkipsSnapshotAndItsUnloadedRelationships() {
    var movie = unreadableRelationships();
    when(movie.isShowNotes()).thenReturn(true);
    when(movie.getMovieDiscord()).thenReturn(null);
    webhook = new DiscordRepositoryImpl(null, false, true, "role");
    new UpdateMessageWebhookUseCaseImpl(
            webhook,
            new DiscordMovieBridge(
                new com.moviemux.movie.adapter.repository.postgres.MovieWebhookAccessAdapter(
                    movies,
                    mock(com.moviemux.user.domain.repository.UserIdentityLookup.class),
                    transactions())))
        .execute(id);
    assertRelationshipsUnread(movie);
    verify(movie, never()).getPortugueseTitle();
  }

  @Test
  void blankMessageReferenceSkipsSnapshotAndItsUnloadedRelationships() {
    var movie = unreadableRelationships();
    when(movie.isShowNotes()).thenReturn(true);
    when(movie.getMovieDiscord()).thenReturn(new MovieDiscordEntity(null, " ", movie));
    webhook = new DiscordRepositoryImpl(null, false, true, "role");
    new UpdateMessageWebhookUseCaseImpl(
            webhook,
            new DiscordMovieBridge(
                new com.moviemux.movie.adapter.repository.postgres.MovieWebhookAccessAdapter(
                    movies,
                    mock(com.moviemux.user.domain.repository.UserIdentityLookup.class),
                    transactions())))
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
    var builder = org.springframework.web.client.RestClient.builder().baseUrl("http://127.0.0.1:1");
    var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
    server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://127.0.0.1:1?wait=true"))
        .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
            "{\"id\":\"remote-message\"}", org.springframework.http.MediaType.APPLICATION_JSON));
    webhook = new DiscordRepositoryImpl(builder.build(), true, false, "role");
    new SendMessageWebhookUseCaseImpl(
            webhook,
            new DiscordMovieBridge(
                new com.moviemux.movie.adapter.repository.postgres.MovieWebhookAccessAdapter(
                    movies,
                    mock(com.moviemux.user.domain.repository.UserIdentityLookup.class),
                    transactions())))
        .execute(id);
    verify(movie, never()).getMovieDiscord();
    verify(movies).saveAndFlush(same(movie));
    server.verify();
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
