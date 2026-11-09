package com.moviemux.discord.adapter.repository.rest.mapper;

import com.moviemux.discord.domain.model.WebhookMovie;
import com.moviemux.discord.domain.repository.WebhookMovieSource;
import com.moviemux.movie.domain.Movie;

/** Transitional conversion, confined to the adapter until persistence models are separated. */
public final class WebhookMovieMapper {
    private WebhookMovieMapper() { }
    public static WebhookMovieSource source(Movie movie) {
        return new WebhookMovieSource() {
            @Override public boolean showNotes() { return movie.isShowNotes(); }
            @Override public String messageId() {
                return movie.getMovieDiscord() == null ? null : movie.getMovieDiscord().getMessageId();
            }
            @Override public WebhookMovie snapshot() { return WebhookMovieMapper.snapshot(movie); }
        };
    }
    public static WebhookMovie snapshot(Movie movie) {
        return new WebhookMovie(movie.getId(), movie.getPortugueseTitle(), movie.getUrlImage(), movie.isShowNotes(),
                movie.getUser() == null ? null : movie.getUser().getName(), movie.getCreatedAt(),
                null, // Message references are read separately, only when an edit requires them.
                movie.getNotes() == null ? null : movie.getNotes().stream()
                        .map(note -> new WebhookMovie.Rating(note.getUser().getName(), note.getNote())).toList());
    }
}
