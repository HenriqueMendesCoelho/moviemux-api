package com.moviemux.board.usecase;

import java.util.UUID;

import com.moviemux.board.domain.BoardMovie;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;
import com.moviemux.board.usecase.exception.DuplicateBoardMovieException;
import com.moviemux.movie.usecase.exception.MovieNotFoundException;

public interface AddMovieToBoardUseCase {

	BoardMovie addMovie(UUID boardPublicId, UUID movieId, UUID requestingUserId)
			throws BoardNotFoundException, BoardNotAuthorizedException, MovieNotFoundException,
			DuplicateBoardMovieException, BoardMemberNotFoundException;

}
