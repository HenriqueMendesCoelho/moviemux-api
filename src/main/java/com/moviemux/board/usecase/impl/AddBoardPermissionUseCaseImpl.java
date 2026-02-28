package com.moviemux.board.usecase.impl;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.moviemux.board.adapter.repository.BoardMemberRepository;
import com.moviemux.board.adapter.repository.BoardPermissionRepository;
import com.moviemux.board.adapter.repository.BoardRepository;
import com.moviemux.board.domain.Board;
import com.moviemux.board.domain.BoardMember;
import com.moviemux.board.domain.BoardMemberStatus;
import com.moviemux.board.domain.BoardPermission;
import com.moviemux.board.domain.BoardPermissionType;
import com.moviemux.board.usecase.AddBoardPermissionUseCase;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AddBoardPermissionUseCaseImpl implements AddBoardPermissionUseCase {

	private final BoardRepository boardRepository;
	private final BoardMemberRepository boardMemberRepository;
	private final BoardPermissionRepository boardPermissionRepository;

	@Override
	public BoardPermission addPermission(UUID boardPublicId, UUID targetUserPublicId, BoardPermissionType permission,
			UUID requestingUserId) throws BoardNotFoundException, BoardNotAuthorizedException, BoardMemberNotFoundException {

		Board board = boardRepository.findByPublicId(boardPublicId)
				.orElseThrow(BoardNotFoundException::new);

		if (!board.getOwner().getId().equals(requestingUserId)) {
			throw new BoardNotAuthorizedException("Only the board owner can manage permissions");
		}

		BoardMember targetMember = boardMemberRepository
				.findByBoardAndUser_IdAndStatus(board, targetUserPublicId, BoardMemberStatus.ACCEPTED)
				.orElseThrow(() -> new BoardMemberNotFoundException("Target user is not an accepted member of this board"));

		boolean alreadyExists = boardPermissionRepository.existsByBoardAndUser_IdAndPermission(
				board, targetUserPublicId, permission);

		if (alreadyExists) {
			return boardPermissionRepository
					.findByBoardAndUser_IdAndPermission(board, targetUserPublicId, permission)
					.orElseThrow();
		}

		BoardPermission boardPermission = BoardPermission.builder()
				.board(board)
				.user(targetMember.getUser())
				.permission(permission)
				.build();

		return boardPermissionRepository.save(boardPermission);
	}
}
