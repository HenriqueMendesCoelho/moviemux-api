package com.moviemux.board.usecase.impl;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.moviemux.board.adapter.repository.BoardMemberRepository;
import com.moviemux.board.adapter.repository.BoardRepository;
import com.moviemux.board.domain.Board;
import com.moviemux.board.domain.BoardMember;
import com.moviemux.board.domain.BoardMemberStatus;
import com.moviemux.board.usecase.AcceptBoardInviteUseCase;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AcceptBoardInviteUseCaseImpl implements AcceptBoardInviteUseCase {

	private final BoardRepository boardRepository;
	private final BoardMemberRepository boardMemberRepository;

	@Override
	public BoardMember accept(UUID boardPublicId, UUID userPublicId, UUID requestingUserId)
			throws BoardNotFoundException, BoardMemberNotFoundException, BoardNotAuthorizedException {

		if (!userPublicId.equals(requestingUserId)) {
			throw new BoardNotAuthorizedException("You can only accept your own invitations");
		}

		Board board = boardRepository.findByPublicId(boardPublicId).orElseThrow(BoardNotFoundException::new);

		BoardMember member = boardMemberRepository
				.findByBoardAndUser_IdAndStatus(board, userPublicId, BoardMemberStatus.PENDING)
				.orElseThrow(() -> new BoardMemberNotFoundException("No pending invitation found for this user"));

		member.setStatus(BoardMemberStatus.ACCEPTED);
		member.setJoinedAt(OffsetDateTime.now());

		return boardMemberRepository.save(member);
	}
}
