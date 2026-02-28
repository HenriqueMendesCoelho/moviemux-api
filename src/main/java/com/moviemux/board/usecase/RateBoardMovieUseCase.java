package com.moviemux.board.usecase;

import java.util.UUID;

import com.moviemux.board.domain.BoardMovieRating;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardMovieNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.movie.usecase.exception.MovieNotFoundException;

public interface RateBoardMovieUseCase {

	BoardMovieRating rate(UUID boardPublicId, UUID movieId, UUID requestingUserId, Integer rating)
			throws BoardNotFoundException, BoardMemberNotFoundException, MovieNotFoundException, BoardMovieNotFoundException;

}
