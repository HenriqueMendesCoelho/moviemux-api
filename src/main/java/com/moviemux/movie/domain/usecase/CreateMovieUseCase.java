package com.moviemux.movie.domain.usecase;
import com.moviemux.movie.domain.command.CreateMovieCommand;
import com.moviemux.movie.domain.model.*;
import com.moviemux.movie.domain.exception.*;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
public interface CreateMovieUseCase { Movie execute(CreateMovieCommand command) throws DuplicatedMovieException; }
