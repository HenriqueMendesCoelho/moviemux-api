package com.moviemux.board.usecase;

import java.util.UUID;

import com.moviemux.board.domain.BoardPermissionType;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;

public interface RemoveBoardPermissionUseCase {

	void removePermission(UUID boardPublicId, UUID targetUserPublicId, BoardPermissionType permission,
			UUID requestingUserId) throws BoardNotFoundException, BoardNotAuthorizedException;

}
