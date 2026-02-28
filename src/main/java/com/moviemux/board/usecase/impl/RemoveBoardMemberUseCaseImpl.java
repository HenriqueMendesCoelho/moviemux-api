package com.moviemux.board.usecase.impl;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.moviemux.board.adapter.repository.BoardMemberRepository;
import com.moviemux.board.adapter.repository.BoardMovieRatingRepository;
import com.moviemux.board.adapter.repository.BoardPermissionRepository;
import com.moviemux.board.adapter.repository.BoardRepository;
import com.moviemux.board.domain.Board;
import com.moviemux.board.domain.BoardMember;
import com.moviemux.board.domain.BoardMemberStatus;
import com.moviemux.board.domain.BoardPermissionType;
import com.moviemux.board.usecase.RemoveBoardMemberUseCase;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;
import com.moviemux.user.domain.User;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RemoveBoardMemberUseCaseImpl implements RemoveBoardMemberUseCase {

	private final BoardRepository boardRepository;
	private final BoardMemberRepository boardMemberRepository;
	private final BoardPermissionRepository boardPermissionRepository;
	private final BoardMovieRatingRepository boardMovieRatingRepository;

	@Override
	@Transactional
	public void remove(UUID boardPublicId, UUID targetUserPublicId, UUID requestingUserId)
			throws BoardNotFoundException, BoardNotAuthorizedException, BoardMemberNotFoundException {

		Board board = boardRepository.findByPublicId(boardPublicId)
				.orElseThrow(BoardNotFoundException::new);

		boolean isOwner = board.getOwner().getId().equals(requestingUserId);

		if (!isOwner) {
			boardMemberRepository.findByBoardAndUser_IdAndStatus(board, requestingUserId, BoardMemberStatus.ACCEPTED)
					.orElseThrow(() -> new BoardNotAuthorizedException("You are not an accepted member of this board"));

			boolean hasPermission = boardPermissionRepository.existsByBoardAndUser_IdAndPermission(
					board, requestingUserId, BoardPermissionType.REMOVE_USER);

			if (!hasPermission) {
				throw new BoardNotAuthorizedException("You do not have permission to remove members from this board");
			}
		}

		if (board.getOwner().getId().equals(targetUserPublicId)) {
			throw new BoardNotAuthorizedException("The board owner cannot be removed");
		}

		BoardMember targetMember = boardMemberRepository.findByBoardAndUser_Id(board, targetUserPublicId)
				.orElseThrow(BoardMemberNotFoundException::new);

		User targetUser = targetMember.getUser();

		boardMovieRatingRepository.deleteByBoardMovie_BoardAndUser(board, targetUser);
		boardPermissionRepository.deleteByBoardAndUser(board, targetUser);
		boardMemberRepository.delete(targetMember);
	}
}
