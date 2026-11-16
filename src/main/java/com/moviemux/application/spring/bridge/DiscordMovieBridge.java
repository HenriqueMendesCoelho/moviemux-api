package com.moviemux.application.spring.bridge;

import com.moviemux.discord.domain.model.WebhookMovie;
import com.moviemux.discord.domain.repository.DiscordMovieRepository;
import com.moviemux.discord.domain.repository.MovieReferenceTarget;
import com.moviemux.movie.domain.repository.MovieWebhookAccess;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DiscordMovieBridge implements DiscordMovieRepository {
  private final MovieWebhookAccess movies;

  @Override
  public Optional<MovieReferenceTarget> find(UUID id) {
    return movies
        .find(id)
        .map(
            target ->
                new MovieReferenceTarget() {
                  @Override
                  public boolean showNotes() {
                    return target.showNotes();
                  }

                  @Override
                  public String messageId() {
                    return target.messageId();
                  }

                  @Override
                  public WebhookMovie snapshot() {
                    var movie = target.snapshot();
                    return new WebhookMovie(
                        movie.id(),
                        movie.title(),
                        movie.imageUrl(),
                        movie.showNotes(),
                        movie.registeredBy(),
                        movie.createdAt(),
                        null,
                        movie.ratings() == null
                            ? null
                            : movie.ratings().stream()
                                .map(
                                    rating ->
                                        new WebhookMovie.Rating(rating.userName(), rating.note()))
                                .toList());
                  }

                  @Override
                  public void saveMessageReference(String messageId) {
                    target.saveMessageReference(messageId);
                  }
                });
  }
}
