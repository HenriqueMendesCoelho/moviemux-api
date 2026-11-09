package com.moviemux.movie.domain.usecase;

import com.moviemux.movie.domain.command.DeleteMovieCommand;
import com.moviemux.movie.domain.exception.*;
import com.moviemux.movie.domain.model.*;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;

public interface DeleteMovieUseCase {
  void execute(DeleteMovieCommand command) throws UserNotAuthorizedException;
}
