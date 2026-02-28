package com.moviemux.board.usecase;

import java.util.UUID;

import com.moviemux.board.domain.Board;

public interface CreateBoardUseCase {

	Board create(Board board, UUID ownerId);

}
