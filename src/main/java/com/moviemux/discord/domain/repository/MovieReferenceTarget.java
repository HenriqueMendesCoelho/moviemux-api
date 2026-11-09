package com.moviemux.discord.domain.repository;

/** One loaded movie and its reference update, without exposing persistence types. */
public interface MovieReferenceTarget extends WebhookMovieSource {
    void saveMessageReference(String messageId);
}
