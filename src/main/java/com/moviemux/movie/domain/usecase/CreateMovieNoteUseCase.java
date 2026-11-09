package com.moviemux.movie.domain.usecase;
import com.moviemux.movie.domain.command.CreateMovieNoteCommand;
import com.moviemux.movie.domain.model.*;
import com.moviemux.movie.domain.exception.*;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
public interface CreateMovieNoteUseCase { MovieNote execute(CreateMovieNoteCommand command) throws MovieNotFoundException, DuplicatedMovieNoteException; }
