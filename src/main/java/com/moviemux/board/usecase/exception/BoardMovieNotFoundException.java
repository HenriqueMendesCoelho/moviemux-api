package com.moviemux.board.usecase.exception;

public class BoardMovieNotFoundException extends ReflectiveOperationException {

	private static final long serialVersionUID = 1L;

	public BoardMovieNotFoundException() {
		super("Movie not found in this board");
	}

	public BoardMovieNotFoundException(String msg) {
		super(msg);
	}
}
