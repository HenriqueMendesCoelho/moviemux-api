package com.moviemux.board.usecase.impl;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.moviemux.board.adapter.repository.BoardRepository;
import com.moviemux.board.domain.Board;
import com.moviemux.board.usecase.GetBoardUseCase;
import com.moviemux.board.usecase.exception.BoardNotFoundException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class GetBoardUseCaseImpl implements GetBoardUseCase {

	private final BoardRepository boardRepository;

	@Override
	public Board get(UUID boardPublicId) throws BoardNotFoundException {
		return boardRepository.findByPublicId(boardPublicId)
				.orElseThrow(BoardNotFoundException::new);
	}
}
