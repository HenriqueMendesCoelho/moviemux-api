package com.moviemux.board.usecase;

import java.util.UUID;

import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;

public interface DeleteBoardUseCase {

	void delete(UUID boardPublicId, UUID requestingUserId) throws BoardNotFoundException, BoardNotAuthorizedException;

}
