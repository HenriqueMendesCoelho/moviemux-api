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
  private final com.moviemux.user.domain.repository.UserIdentityLookup identities;
  private final org.springframework.transaction.support.TransactionTemplate transactions;

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
                    return transactions.execute(
                        status -> {
                          var snapshotMovie = repository.findById(movie.getId()).orElseThrow();
                          return new MovieWebhookSnapshot(
                              movie.getId(),
                              movie.getPortugueseTitle(),
                              movie.getUrlImage(),
                              movie.isShowNotes(),
                              movie.getUserId() == null
                                  ? null
                                  : identities.find(movie.getUserId()).orElseThrow().name(),
                              movie.getCreatedAt(),
                              snapshotMovie.getNotes() == null
                                  ? null
                                  : snapshotMovie.getNotes().stream()
                                      .map(
                                          note ->
                                              new MovieWebhookSnapshot.Rating(
                                                  identities
                                                      .find(note.getId().getUserId())
                                                      .orElseThrow()
                                                      .name(),
                                                  note.getNote()))
                                      .toList());
                        });
                  }

                  @Override
                  public void saveMessageReference(String messageId) {
                    movie.setMovieDiscord(new MovieDiscordEntity(null, messageId, movie));
                    repository.saveAndFlush(movie);
                  }
                });
  }
}
