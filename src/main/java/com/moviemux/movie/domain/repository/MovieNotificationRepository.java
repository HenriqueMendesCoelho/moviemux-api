package com.moviemux.movie.domain.repository;

import com.moviemux.movie.domain.model.NotificationNote;
import java.util.UUID;

public interface MovieNotificationRepository {
    enum Delivery { SENT, SKIPPED, DEFERRED, FAILED }
    void notifyAllMovies(String event);
    Delivery notifyMovie(UUID movieId, String event, NotificationNote note, String emmitedByUserId);
}
