package com.moviemux.board.usecase;

import java.util.UUID;

import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardMovieNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.movie.usecase.exception.MovieNotFoundException;

public interface DeleteBoardMovieRatingUseCase {

	void deleteRating(UUID boardPublicId, UUID movieId, UUID requestingUserId)
			throws BoardNotFoundException, BoardMemberNotFoundException, MovieNotFoundException, BoardMovieNotFoundException;

}
