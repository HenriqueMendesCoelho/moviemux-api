package com.moviemux.integrationports;

import com.moviemux.application.spring.bridge.DiscordMovieBridge;
import com.moviemux.discord.domain.DiscordWebhookInfo;
import com.moviemux.discord.domain.model.WebhookDelivery;
import com.moviemux.discord.domain.repository.WebhookRepository;
import com.moviemux.discord.usecase.impl.SendMessageWebhookUseCaseImpl;
import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.domain.Movie;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscordMovieBridgeTest {
    private final MovieRepository movies = mock(MovieRepository.class);
    private final WebhookRepository webhook = mock(WebhookRepository.class);
    private final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private Movie movie(String title) { return Movie.builder().id(id).portugueseTitle(title).build(); }

    @Test void absentMovieDoesNotCreateOperationTarget() {
        when(movies.findById(id)).thenReturn(Optional.empty());
        assertThat(new DiscordMovieBridge(movies).find(id)).isEmpty();
        verify(movies).findById(id);
        verifyNoMoreInteractions(movies);
    }
    @Test void remoteCallbackSavesSameOriginallyLoadedEntityWithoutRereading() {
        var original = movie("Original title");
        var replacement = movie("Concurrent replacement");
        when(movies.findById(id)).thenReturn(Optional.of(original));
        when(webhook.send(any())).thenAnswer(invocation -> {
            assertThat(invocation.<com.moviemux.discord.domain.repository.WebhookMovieSource>getArgument(0).snapshot().title()).isEqualTo("Original title");
            // Simulate a changed next query while the HTTP request is in progress.
            when(movies.findById(id)).thenReturn(Optional.of(replacement));
            return WebhookDelivery.sent(new DiscordWebhookInfo("remote-message"));
        });
        new SendMessageWebhookUseCaseImpl(webhook, new DiscordMovieBridge(movies)).sendMovieMessage(id);
        verify(movies, times(1)).findById(id);
        verify(movies).saveAndFlush(same(original));
        verify(movies, never()).saveAndFlush(same(replacement));
        assertThat(original.getMovieDiscord().getMessageId()).isEqualTo("remote-message");
        assertThat(original.getMovieDiscord().getMovie()).isSameAs(original);
        assertThat(replacement.getMovieDiscord()).isNull();
        var order = inOrder(movies, webhook);
        order.verify(movies).findById(id);
        order.verify(webhook).send(any());
        order.verify(movies).saveAndFlush(same(original));
    }
    @Test void failedSaveAfterRemoteSuccessDoesNotRereadOrRetryRemoteEffect() {
        var original = movie("Movie");
        when(movies.findById(id)).thenReturn(Optional.of(original));
        when(webhook.send(any())).thenReturn(WebhookDelivery.sent(new DiscordWebhookInfo("remote-message")));
        when(movies.saveAndFlush(same(original))).thenThrow(new IllegalStateException("synthetic save failure"));
        assertThatThrownBy(() -> new SendMessageWebhookUseCaseImpl(webhook, new DiscordMovieBridge(movies)).sendMovieMessage(id))
                .isInstanceOf(IllegalStateException.class).hasMessage("synthetic save failure");
        verify(movies, times(1)).findById(id);
        verify(webhook, times(1)).send(any());
        verify(movies, times(1)).saveAndFlush(same(original));
    }
}
