package com.moviemux.movie.domain.repository;

import com.moviemux.movie.domain.model.MovieWebhookSnapshot;
import java.util.Optional;
import java.util.UUID;

public interface MovieWebhookAccess {
  interface Target {
    boolean showNotes();

    String messageId();

    MovieWebhookSnapshot snapshot();

    void saveMessageReference(String messageId);
  }

  Optional<Target> find(UUID movieId);
}
