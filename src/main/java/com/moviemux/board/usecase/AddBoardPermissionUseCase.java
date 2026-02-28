package com.moviemux.board.usecase;

import java.util.UUID;

import com.moviemux.board.domain.BoardPermission;
import com.moviemux.board.domain.BoardPermissionType;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;

public interface AddBoardPermissionUseCase {

	BoardPermission addPermission(UUID boardPublicId, UUID targetUserPublicId, BoardPermissionType permission,
			UUID requestingUserId) throws BoardNotFoundException, BoardNotAuthorizedException, BoardMemberNotFoundException;

}
