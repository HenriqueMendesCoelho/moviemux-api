package com.moviemux.board.usecase.exception;

public class DuplicateBoardMovieException extends ReflectiveOperationException {

	private static final long serialVersionUID = 1L;

	public DuplicateBoardMovieException() {
		super("Movie is already in the board");
	}

	public DuplicateBoardMovieException(String msg) {
		super(msg);
	}
}
