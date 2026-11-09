package com.moviemux.movie.adapter.notification;

import com.moviemux.application.spring.effects.AfterCommitEffects;
import com.moviemux.movie.domain.model.NotificationNote;
import com.moviemux.movie.domain.repository.MovieNotificationRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class AfterCommitMovieNotifications implements MovieNotificationRepository {
  private final MovieNotificationRepository delegate;
  private final AfterCommitEffects effects;

  public AfterCommitMovieNotifications(
      @Qualifier("movieSocketRestRespositoryImpl") MovieNotificationRepository delegate,
      AfterCommitEffects effects) {
    this.delegate = delegate;
    this.effects = effects;
  }

  @Override
  public void notifyAllMovies(String event) {
    effects.submit("movie socket notification", () -> delegate.notifyAllMovies(event));
  }

  @Override
  public Delivery notifyMovie(
      UUID movieId, String event, NotificationNote note, String emittedByUserId) {
    if (movieId == null || event == null || event.isBlank()) {
      return Delivery.SKIPPED;
    }
    // NotificationNote is immutable; no live model/entity can change the payload after
    // registration.
    AtomicReference<Delivery> immediateResult = new AtomicReference<>(Delivery.FAILED);
    boolean deferred =
        effects.submit(
            "movie socket notification",
            () -> immediateResult.set(delegate.notifyMovie(movieId, event, note, emittedByUserId)));
    return deferred ? Delivery.DEFERRED : immediateResult.get();
  }
}
