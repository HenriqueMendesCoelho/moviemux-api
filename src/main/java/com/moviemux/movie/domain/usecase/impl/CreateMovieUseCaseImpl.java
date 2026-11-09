package com.moviemux.movie.domain.usecase.impl;

import com.moviemux.movie.domain.command.CreateMovieCommand;
import com.moviemux.movie.domain.exception.DuplicatedMovieException;
import com.moviemux.movie.domain.model.Movie;
import com.moviemux.movie.domain.model.MovieActor;
import com.moviemux.movie.domain.model.NotificationNote;
import com.moviemux.movie.domain.repository.MovieAnnouncement;
import com.moviemux.movie.domain.repository.MovieIdentityRepository;
import com.moviemux.movie.domain.repository.MovieNotificationRepository;
import com.moviemux.movie.domain.repository.MovieRepository;
import com.moviemux.movie.domain.usecase.CreateMovieUseCase;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateMovieUseCaseImpl implements CreateMovieUseCase {

  private final MovieRepository repository;

  private final MovieIdentityRepository userRepository;

  private final MovieAnnouncement sendMessageWebhook;

  private final MovieNotificationRepository socketRepository;

  @Override
  public Movie execute(CreateMovieCommand command) throws DuplicatedMovieException {
    Movie movie = command.movie();
    UUID userId = command.userId();

    if (repository.findByTmdbId(movie.getTmdbId()) != null) {
      throw new DuplicatedMovieException("This tmdb id is already registered");
    }

    MovieActor user = userRepository.findById(userId).orElse(null);
    movie.setUser(user);
    movie.setShowNotes(false);
    userRepository.flush(user.getId());
    Movie movieCreated = repository.save(movie);
    sendMessageDiscord(movieCreated.getId());
    emitEventSocket();

    return movieCreated;
  }

  private void sendMessageDiscord(UUID movieId) {
    try {
      sendMessageWebhook.created(movieId);
    } catch (Exception e) {
      log.error("Error to send message on discord raised: ", e);
    }
  }

  private void emitEventSocket() {
    try {
      socketRepository.notifyAllMovies(null);
    } catch (Exception e) {
      log.error("Error to emit event on movie creation raised: ", e);
    }
  }
}
