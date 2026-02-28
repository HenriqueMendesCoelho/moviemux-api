package com.moviemux.board.usecase.impl;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.moviemux.board.adapter.repository.BoardMemberRepository;
import com.moviemux.board.adapter.repository.BoardPermissionRepository;
import com.moviemux.board.adapter.repository.BoardRepository;
import com.moviemux.board.domain.Board;
import com.moviemux.board.domain.BoardMember;
import com.moviemux.board.domain.BoardMemberStatus;
import com.moviemux.board.domain.BoardPermissionType;
import com.moviemux.board.usecase.InviteUserToBoardUseCase;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;
import com.moviemux.board.usecase.exception.DuplicateBoardMemberException;
import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.domain.User;
import com.moviemux.user.usecase.exception.UserNotFoundException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class InviteUserToBoardUseCaseImpl implements InviteUserToBoardUseCase {

	private final BoardRepository boardRepository;
	private final BoardMemberRepository boardMemberRepository;
	private final BoardPermissionRepository boardPermissionRepository;
	private final UserRepository userRepository;

	@Override
	public BoardMember invite(UUID boardPublicId, UUID targetUserPublicId, UUID inviterUserId)
			throws BoardNotFoundException, BoardNotAuthorizedException, DuplicateBoardMemberException,
			UserNotFoundException, BoardMemberNotFoundException {

		Board board = boardRepository.findByPublicId(boardPublicId)
				.orElseThrow(BoardNotFoundException::new);

		boolean isOwner = board.getOwner().getId().equals(inviterUserId);

		if (!isOwner) {
			boardMemberRepository.findByBoardAndUser_IdAndStatus(board, inviterUserId, BoardMemberStatus.ACCEPTED)
					.orElseThrow(() -> new BoardMemberNotFoundException("Inviter is not an accepted board member"));

			boolean hasPermission = boardPermissionRepository.existsByBoardAndUser_IdAndPermission(
					board, inviterUserId, BoardPermissionType.INVITE_USER);

			if (!hasPermission) {
				throw new BoardNotAuthorizedException("You do not have permission to invite users to this board");
			}
		}

		User targetUser = userRepository.findById(targetUserPublicId)
				.orElseThrow(UserNotFoundException::new);

		boolean alreadyExists = boardMemberRepository.existsByBoardAndUser_IdAndStatusIn(
				board, targetUserPublicId, List.of(BoardMemberStatus.ACCEPTED, BoardMemberStatus.PENDING));

		if (alreadyExists) {
			throw new DuplicateBoardMemberException();
		}

		BoardMember member = BoardMember.builder()
				.board(board)
				.user(targetUser)
				.status(BoardMemberStatus.PENDING)
				.build();

		return boardMemberRepository.save(member);
	}
}
