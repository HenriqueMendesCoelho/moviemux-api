package com.moviemux.integrationports;

import com.moviemux.kronusintegrationtool.domain.model.*;
import com.moviemux.kronusintegrationtool.domain.repository.*;
import com.moviemux.kronusintegrationtool.domain.usecase.impl.*;
import com.moviemux.discord.domain.model.*;
import com.moviemux.discord.domain.repository.*;
import com.moviemux.discord.domain.model.DiscordWebhookInfo;
import com.moviemux.discord.domain.usecase.impl.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IntegrationPortTest {
    private final KronusCatalogRepository catalog = mock(KronusCatalogRepository.class);
    private final WebhookRepository webhook = mock(WebhookRepository.class);
    private final DiscordMovieRepository movies = mock(DiscordMovieRepository.class);
    private final MovieReferenceTarget target = mock(MovieReferenceTarget.class);
    private final UUID movieId = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private WebhookMovie movie(boolean visible) {
        return new WebhookMovie(movieId, "Movie", "/image", visible, "Viewer", null, "message", List.of());
    }
    @Test void providersPilotInvokesIndependentPortWithUnchangedIdAndResult() {
        var providers = new WatchProviders(42L, Map.of());
        when(catalog.getWatchProviders(42L)).thenReturn(providers);
        assertThat(new SearchWatchProviedersUseCaseImpl(catalog).execute(42L)).isSameAs(providers);
        verifyNoMoreInteractionsAfterProvider();
    }
    private void verifyNoMoreInteractionsAfterProvider() { verify(catalog).getWatchProviders(42L); verifyNoMoreInteractions(catalog); }
    @Test void providersTechnicalFailureRemainsFailure() {
        var failure = new IllegalStateException("synthetic failure");
        when(catalog.getWatchProviders(42L)).thenThrow(failure);
        assertThatThrownBy(() -> new SearchWatchProviedersUseCaseImpl(catalog).execute(42L)).isSameAs(failure);
    }
    @Test void movieSearchPreservesAdultFilterAndPaging() {
        var result = new MovieSearch(2, List.of(), 3, 4);
        when(catalog.searchByName("Movie", 2, "pt-Br", false)).thenReturn(result);
        assertThat(new com.moviemux.kronusintegrationtool.domain.usecase.SearchCatalogMoviesUseCase(catalog).execute("Movie", 2, "pt-Br")).isSameAs(result);
    }
    @Test void creditsAndSummaryPassThroughDomainResults() {
        var credits = new Credit(List.of(), List.of());
        var summary = MovieSummary.builder().tmdbId(42L).build();
        when(catalog.getCredits(42L)).thenReturn(credits);
        when(catalog.movieSummary(42L)).thenReturn(summary);
        assertThat(new SearchCreditUseCaseImpl(catalog).execute(42L)).isSameAs(credits);
        assertThat(new MovieSumarryUseCaseImpl(catalog).execute(42L)).isSameAs(summary);
    }
    @Test void absentMovieDoesNotSendOrSaveReference() {
        when(movies.find(movieId)).thenReturn(Optional.empty());
        new SendMessageWebhookUseCaseImpl(webhook, movies).execute(movieId);
        verifyNoInteractions(webhook);
        verify(target, never()).saveMessageReference(any());
    }
    @Test void disabledWebhookDoesNotSaveReference() {
        var movie = movie(false);
        when(movies.find(movieId)).thenReturn(Optional.of(target));
        when(target.showNotes()).thenReturn(movie.showNotes());
        when(webhook.send(target)).thenReturn(WebhookDelivery.skipped(WebhookDelivery.Status.DISABLED));
        new SendMessageWebhookUseCaseImpl(webhook, movies).execute(movieId);
        verify(target, never()).saveMessageReference(any());
    }
    @Test void referenceIsSavedOnlyAfterSuccessfulRemoteSend() {
        var movie = movie(false);
        when(movies.find(movieId)).thenReturn(Optional.of(target));
        when(target.showNotes()).thenReturn(movie.showNotes());
        when(webhook.send(target)).thenReturn(WebhookDelivery.sent(new DiscordWebhookInfo("new-message")));
        new SendMessageWebhookUseCaseImpl(webhook, movies).execute(movieId);
        var order = inOrder(movies, target, webhook);
        order.verify(movies).find(movieId);
        order.verify(webhook).send(target);
        order.verify(target).saveMessageReference("new-message");
    }
    @Test void remoteFailureDoesNotSaveReferenceOrRetry() {
        var movie = movie(false);
        when(movies.find(movieId)).thenReturn(Optional.of(target));
        when(target.showNotes()).thenReturn(movie.showNotes());
        when(webhook.send(target)).thenThrow(new IllegalStateException("remote"));
        assertThatThrownBy(() -> new SendMessageWebhookUseCaseImpl(webhook, movies).execute(movieId)).isInstanceOf(IllegalStateException.class);
        verify(webhook).send(target);
        verify(target, never()).saveMessageReference(any());
    }
    @Test void localFailureAfterRemoteSendDoesNotRepeatWebhook() {
        var movie = movie(false);
        when(movies.find(movieId)).thenReturn(Optional.of(target));
        when(target.showNotes()).thenReturn(movie.showNotes());
        when(webhook.send(target)).thenReturn(WebhookDelivery.sent(new DiscordWebhookInfo("new-message")));
        doThrow(new IllegalStateException("local save")).when(target).saveMessageReference("new-message");
        assertThatThrownBy(() -> new SendMessageWebhookUseCaseImpl(webhook, movies).execute(movieId)).isInstanceOf(IllegalStateException.class);
        verify(webhook, times(1)).send(target);
    }
    @Test void invisibleMovieDoesNotUpdateRemoteMessage() {
        when(movies.find(movieId)).thenReturn(Optional.of(target));
        when(target.showNotes()).thenReturn(false);
        new UpdateMessageWebhookUseCaseImpl(webhook, movies).execute(movieId);
        verifyNoInteractions(webhook);
    }
    @Test void visibleMovieUpdatesWithoutCreatingReference() {
        var movie = movie(true);
        when(movies.find(movieId)).thenReturn(Optional.of(target));
        when(target.showNotes()).thenReturn(movie.showNotes());
        new UpdateMessageWebhookUseCaseImpl(webhook, movies).execute(movieId);
        verify(webhook).edit(target);
        verify(target, never()).saveMessageReference(any());
    }
    @Test void webhookSnapshotHasNoMutableCollectionAlias() {
        var ratings = new java.util.ArrayList<WebhookMovie.Rating>();
        ratings.add(new WebhookMovie.Rating("Viewer", 7));
        var movie = new WebhookMovie(movieId, "Movie", null, false, null, null, null, ratings);
        ratings.clear();
        assertThat(movie.ratings()).hasSize(1);
        assertThatThrownBy(() -> movie.ratings().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
