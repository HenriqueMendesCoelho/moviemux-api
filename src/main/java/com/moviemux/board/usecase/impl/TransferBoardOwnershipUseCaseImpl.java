package com.moviemux.board.usecase.impl;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.moviemux.board.adapter.repository.BoardMemberRepository;
import com.moviemux.board.adapter.repository.BoardRepository;
import com.moviemux.board.domain.Board;
import com.moviemux.board.domain.BoardMember;
import com.moviemux.board.domain.BoardMemberStatus;
import com.moviemux.board.usecase.TransferBoardOwnershipUseCase;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class TransferBoardOwnershipUseCaseImpl implements TransferBoardOwnershipUseCase {

	private final BoardRepository boardRepository;
	private final BoardMemberRepository boardMemberRepository;

	@Override
	@Transactional
	public Board transfer(UUID boardPublicId, UUID newOwnerPublicId, UUID currentOwnerUserId)
			throws BoardNotFoundException, BoardNotAuthorizedException, BoardMemberNotFoundException {

		Board board = boardRepository.findByPublicId(boardPublicId)
				.orElseThrow(BoardNotFoundException::new);

		if (!board.getOwner().getId().equals(currentOwnerUserId)) {
			throw new BoardNotAuthorizedException("Only the board owner can transfer ownership");
		}

		BoardMember newOwnerMember = boardMemberRepository
				.findByBoardAndUser_IdAndStatus(board, newOwnerPublicId, BoardMemberStatus.ACCEPTED)
				.orElseThrow(() -> new BoardMemberNotFoundException("New owner must be an accepted member of the board"));

		board.setOwner(newOwnerMember.getUser());

		return boardRepository.saveAndFlush(board);
	}
}
