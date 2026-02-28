package com.moviemux.board.usecase;

import java.util.UUID;

import com.moviemux.board.domain.Board;
import com.moviemux.board.usecase.exception.BoardNotFoundException;

public interface GetBoardUseCase {

	Board get(UUID boardPublicId) throws BoardNotFoundException;

}
