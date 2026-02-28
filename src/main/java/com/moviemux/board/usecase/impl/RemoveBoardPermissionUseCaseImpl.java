package com.moviemux.board.usecase.impl;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.moviemux.board.adapter.repository.BoardPermissionRepository;
import com.moviemux.board.adapter.repository.BoardRepository;
import com.moviemux.board.domain.Board;
import com.moviemux.board.domain.BoardPermissionType;
import com.moviemux.board.usecase.RemoveBoardPermissionUseCase;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RemoveBoardPermissionUseCaseImpl implements RemoveBoardPermissionUseCase {

	private final BoardRepository boardRepository;
	private final BoardPermissionRepository boardPermissionRepository;

	@Override
	public void removePermission(UUID boardPublicId, UUID targetUserPublicId, BoardPermissionType permission,
			UUID requestingUserId) throws BoardNotFoundException, BoardNotAuthorizedException {

		Board board = boardRepository.findByPublicId(boardPublicId)
				.orElseThrow(BoardNotFoundException::new);

		if (!board.getOwner().getId().equals(requestingUserId)) {
			throw new BoardNotAuthorizedException("Only the board owner can manage permissions");
		}

		boardPermissionRepository.deleteByBoardAndUser_IdAndPermission(board, targetUserPublicId, permission);
	}
}
