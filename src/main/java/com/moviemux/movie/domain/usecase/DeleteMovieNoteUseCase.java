package com.moviemux.movie.domain.usecase;
import com.moviemux.movie.domain.command.DeleteMovieNoteCommand;
import com.moviemux.movie.domain.model.*;
import com.moviemux.movie.domain.exception.*;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
public interface DeleteMovieNoteUseCase { void execute(DeleteMovieNoteCommand command); }
