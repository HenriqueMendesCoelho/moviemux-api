package com.moviemux.board.usecase.exception;

public class BoardNotFoundException extends ReflectiveOperationException {

	private static final long serialVersionUID = 1L;

	public BoardNotFoundException() {
		super("Board not found");
	}

	public BoardNotFoundException(String msg) {
		super(msg);
	}
}
