package com.moviemux.movie.domain.usecase.impl;

import com.moviemux.movie.domain.command.UpdateMovieCommand;
import com.moviemux.movie.domain.exception.MovieNotFoundException;
import com.moviemux.movie.domain.model.Movie;
import com.moviemux.movie.domain.model.MovieActor;
import com.moviemux.movie.domain.model.NotificationNote;
import com.moviemux.movie.domain.repository.MovieAnnouncement;
import com.moviemux.movie.domain.repository.MovieIdentityRepository;
import com.moviemux.movie.domain.repository.MovieNotificationRepository;
import com.moviemux.movie.domain.repository.MovieRepository;
import com.moviemux.movie.domain.usecase.UpdateMovieUseCase;
import com.moviemux.user.domain.Role;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateMovieUseCaseImpl implements UpdateMovieUseCase {

  private final MovieRepository repository;

  private final MovieIdentityRepository userRepository;

  private final MovieAnnouncement updateMessageWebhookUseCase;

  private final MovieNotificationRepository movieSocketRespository;

  @Override
  public Movie execute(UpdateMovieCommand command)
      throws MovieNotFoundException, UserNotAuthorizedException {
    Movie movie = command.movie();
    UUID id = command.id();
    String userEmail = command.userEmail();
    MovieActor user = userRepository.findByEmail(userEmail);
    Movie movieToUpdate = repository.findById(id).orElse(null);

    if (movieToUpdate == null) {
      throw new MovieNotFoundException();
    }

    if (movieToUpdate.getUser() == null && !user.getRoles().contains(Role.ADM)) {
      throw new UserNotAuthorizedException();
    }

    if (!user.getRoles().contains(Role.ADM) && movieToUpdate.getUser().getId() != user.getId()) {
      throw new UserNotAuthorizedException();
    }

    final boolean emitEvent = movie.isShowNotes() != movieToUpdate.isShowNotes();

    movieToUpdate.setDescription(movie.getDescription());
    movieToUpdate.setDirector(movie.getDirector());
    movieToUpdate.setEnglishUrlTrailer(movie.getEnglishUrlTrailer());
    movieToUpdate.setOriginalTitle(movie.getOriginalTitle());
    movieToUpdate.setPortugueseTitle(movie.getPortugueseTitle());
    movieToUpdate.setPortugueseUrlTrailer(movie.getPortugueseUrlTrailer());
    movieToUpdate.setTmdbId(movie.getTmdbId());
    movieToUpdate.setUrlImage(movie.getUrlImage());
    movieToUpdate.setReleaseDate(movie.getReleaseDate());
    movieToUpdate.setImdbId(movie.getImdbId());
    movieToUpdate.setGenres(movie.getGenres());
    movieToUpdate.setRuntime(movie.getRuntime());
    movieToUpdate.setShowNotes(movie.isShowNotes());

    Movie movieUpdated = repository.save(movieToUpdate);
    updateMessageWebhookUseCase.updated(movieToUpdate.getId());
    if (emitEvent) {
      sendEventSocket(movieUpdated);
    }

    return movieUpdated;
  }

  private void sendEventSocket(Movie movie) {
    try {
      movieSocketRespository.notifyMovie(movie.getId(), "update-movie", null, null);
    } catch (Exception e) {
      log.error("Error to emit event on movie update (UpdateMovieUseCaseImpl): ", e);
    }
  }
}
