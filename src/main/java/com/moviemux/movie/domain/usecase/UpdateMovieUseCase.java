package com.moviemux.movie.domain.usecase;

import com.moviemux.movie.domain.command.UpdateMovieCommand;
import com.moviemux.movie.domain.exception.*;
import com.moviemux.movie.domain.model.*;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;

public interface UpdateMovieUseCase {
  Movie execute(UpdateMovieCommand command)
      throws MovieNotFoundException, UserNotAuthorizedException;
}
