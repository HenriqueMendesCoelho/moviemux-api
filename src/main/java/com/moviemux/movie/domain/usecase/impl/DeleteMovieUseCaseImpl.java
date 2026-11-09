package com.moviemux.movie.domain.usecase.impl;

import com.moviemux.movie.domain.command.DeleteMovieCommand;
import com.moviemux.movie.domain.model.Movie;
import com.moviemux.movie.domain.model.MovieActor;
import com.moviemux.movie.domain.model.NotificationNote;
import com.moviemux.movie.domain.repository.MovieIdentityRepository;
import com.moviemux.movie.domain.repository.MovieNotificationRepository;
import com.moviemux.movie.domain.repository.MovieRepository;
import com.moviemux.movie.domain.usecase.DeleteMovieUseCase;
import com.moviemux.user.domain.Role;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteMovieUseCaseImpl implements DeleteMovieUseCase {

  private final MovieRepository repository;

  private final MovieIdentityRepository userRepository;

  private final MovieNotificationRepository socketRepository;

  @Override
  public void execute(DeleteMovieCommand command) throws UserNotAuthorizedException {
    UUID id = command.id();
    UUID idUserLoged = command.actorId();
    Movie movie = repository.findById(id).orElse(null);

    if (movie == null) {
      return;
    }

    MovieActor user = userRepository.findById(idUserLoged).orElse(null);

    if (user == null) {
      return;
    }

    if (movie.getUser() == null && !user.getRoles().contains(Role.ADM)) {
      throw new UserNotAuthorizedException("user can not delete this movie");
    }

    if (!user.getRoles().contains(Role.ADM) && movie.getUser().getId() != user.getId()) {
      throw new UserNotAuthorizedException("user can not delete this movie");
    }

    repository.delete(movie);
    emitEventSocket(id, idUserLoged);
  }

  private void emitEventSocket(UUID movieId, UUID idUserLoged) {
    try {
      socketRepository.notifyAllMovies(null);
      socketRepository.notifyMovie(movieId, "deleted-movie", null, idUserLoged.toString());
    } catch (Exception e) {
      log.error("Error to emit event on movie creation raised: ", e);
    }
  }
}
