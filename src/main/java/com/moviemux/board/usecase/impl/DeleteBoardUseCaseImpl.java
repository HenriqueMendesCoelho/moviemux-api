package com.moviemux.board.usecase.impl;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.moviemux.board.adapter.repository.BoardRepository;
import com.moviemux.board.domain.Board;
import com.moviemux.board.usecase.DeleteBoardUseCase;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DeleteBoardUseCaseImpl implements DeleteBoardUseCase {

	private final BoardRepository boardRepository;

	@Override
	public void delete(UUID boardPublicId, UUID requestingUserId)
			throws BoardNotFoundException, BoardNotAuthorizedException {

		Board board = boardRepository.findByPublicId(boardPublicId)
				.orElseThrow(BoardNotFoundException::new);

		if (!board.getOwner().getId().equals(requestingUserId)) {
			throw new BoardNotAuthorizedException("Only the board owner can delete the board");
		}

		boardRepository.delete(board);
	}
}
