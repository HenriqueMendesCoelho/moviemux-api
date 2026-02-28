package com.moviemux.board.usecase;

import java.util.UUID;

import com.moviemux.board.domain.Board;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;

public interface TransferBoardOwnershipUseCase {

	Board transfer(UUID boardPublicId, UUID newOwnerPublicId, UUID currentOwnerUserId)
			throws BoardNotFoundException, BoardNotAuthorizedException, BoardMemberNotFoundException;

}
