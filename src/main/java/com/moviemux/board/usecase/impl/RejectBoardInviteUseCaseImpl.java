package com.moviemux.board.usecase.impl;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.moviemux.board.adapter.repository.BoardMemberRepository;
import com.moviemux.board.adapter.repository.BoardRepository;
import com.moviemux.board.domain.Board;
import com.moviemux.board.domain.BoardMember;
import com.moviemux.board.domain.BoardMemberStatus;
import com.moviemux.board.usecase.RejectBoardInviteUseCase;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RejectBoardInviteUseCaseImpl implements RejectBoardInviteUseCase {

	private final BoardRepository boardRepository;
	private final BoardMemberRepository boardMemberRepository;

	@Override
	public BoardMember reject(UUID boardPublicId, UUID userPublicId, UUID requestingUserId)
			throws BoardNotFoundException, BoardMemberNotFoundException, BoardNotAuthorizedException {

		if (!userPublicId.equals(requestingUserId)) {
			throw new BoardNotAuthorizedException("You can only reject your own invitations");
		}

		Board board = boardRepository.findByPublicId(boardPublicId)
				.orElseThrow(BoardNotFoundException::new);

		BoardMember member = boardMemberRepository
				.findByBoardAndUser_IdAndStatus(board, userPublicId, BoardMemberStatus.PENDING)
				.orElseThrow(() -> new BoardMemberNotFoundException("No pending invitation found for this user"));

		member.setStatus(BoardMemberStatus.REJECTED);

		return boardMemberRepository.save(member);
	}
}
