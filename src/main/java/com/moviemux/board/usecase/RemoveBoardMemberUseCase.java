package com.moviemux.board.usecase;

import java.util.UUID;

import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;

public interface RemoveBoardMemberUseCase {

	void remove(UUID boardPublicId, UUID targetUserPublicId, UUID requestingUserId)
			throws BoardNotFoundException, BoardNotAuthorizedException, BoardMemberNotFoundException;

}
