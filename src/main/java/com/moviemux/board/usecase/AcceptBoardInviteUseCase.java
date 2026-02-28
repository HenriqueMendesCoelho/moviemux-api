package com.moviemux.board.usecase;

import java.util.UUID;

import com.moviemux.board.domain.BoardMember;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;

public interface AcceptBoardInviteUseCase {

	BoardMember accept(UUID boardPublicId, UUID userPublicId, UUID requestingUserId)
			throws BoardNotFoundException, BoardMemberNotFoundException, BoardNotAuthorizedException;

}
