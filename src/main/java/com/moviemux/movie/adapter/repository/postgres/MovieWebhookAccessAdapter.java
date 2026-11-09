package com.moviemux.movie.adapter.repository.postgres;

import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.adapter.repository.postgres.entity.MovieDiscordEntity;
import com.moviemux.movie.domain.model.MovieWebhookSnapshot;
import com.moviemux.movie.domain.repository.MovieWebhookAccess;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MovieWebhookAccessAdapter implements MovieWebhookAccess {
  private final MovieRepository repository;

  @Override
  public Optional<Target> find(UUID id) {
    return repository
        .findById(id)
        .map(
            movie ->
                new Target() {
                  @Override
                  public boolean showNotes() {
                    return movie.isShowNotes();
                  }

                  @Override
                  public String messageId() {
                    return movie.getMovieDiscord() == null
                        ? null
                        : movie.getMovieDiscord().getMessageId();
                  }

                  @Override
                  public MovieWebhookSnapshot snapshot() {
                    return new MovieWebhookSnapshot(
                        movie.getId(),
                        movie.getPortugueseTitle(),
                        movie.getUrlImage(),
                        movie.isShowNotes(),
                        movie.getUser() == null ? null : movie.getUser().getName(),
                        movie.getCreatedAt(),
                        movie.getNotes() == null
                            ? null
                            : movie.getNotes().stream()
                                .map(
                                    note ->
                                        new MovieWebhookSnapshot.Rating(
                                            note.getUser().getName(), note.getNote()))
                                .toList());
                  }

                  @Override
                  public void saveMessageReference(String messageId) {
                    movie.setMovieDiscord(new MovieDiscordEntity(null, messageId, movie));
                    repository.saveAndFlush(movie);
                  }
                });
  }
}
