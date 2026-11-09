package com.moviemux.discord.domain.repository;

import com.moviemux.discord.domain.model.WebhookMovie;

/** Metadata can be read without materializing rating or user relationships. */
public interface WebhookMovieSource {
  boolean showNotes();

  String messageId();

  WebhookMovie snapshot();
}
