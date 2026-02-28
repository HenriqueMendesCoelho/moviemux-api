package com.moviemux.board.usecase;

import java.util.UUID;

import com.moviemux.board.usecase.exception.BoardMovieNotFoundException;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;

public interface RemoveMovieFromBoardUseCase {

	void removeMovie(UUID boardPublicId, UUID movieId, UUID requestingUserId)
			throws BoardNotFoundException, BoardNotAuthorizedException, BoardMovieNotFoundException,
			BoardMemberNotFoundException;

}
