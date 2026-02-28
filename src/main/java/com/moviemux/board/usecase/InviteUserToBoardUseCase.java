package com.moviemux.board.usecase;

import java.util.UUID;

import com.moviemux.board.domain.BoardMember;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;
import com.moviemux.board.usecase.exception.DuplicateBoardMemberException;
import com.moviemux.user.usecase.exception.UserNotFoundException;

public interface InviteUserToBoardUseCase {

	BoardMember invite(UUID boardPublicId, UUID targetUserPublicId, UUID inviterUserId)
			throws BoardNotFoundException, BoardNotAuthorizedException, DuplicateBoardMemberException,
			UserNotFoundException, BoardMemberNotFoundException;

}
