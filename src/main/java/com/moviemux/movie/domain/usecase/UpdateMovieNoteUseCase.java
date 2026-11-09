package com.moviemux.movie.domain.usecase;
import com.moviemux.movie.domain.command.UpdateMovieNoteCommand;
import com.moviemux.movie.domain.model.*;
import com.moviemux.movie.domain.exception.*;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
public interface UpdateMovieNoteUseCase { MovieNote execute(UpdateMovieNoteCommand command) throws MovieNoteNotFoundException; }
