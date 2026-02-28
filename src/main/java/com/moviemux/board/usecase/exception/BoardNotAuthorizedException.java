package com.moviemux.board.usecase.exception;

public class BoardNotAuthorizedException extends ReflectiveOperationException {

	private static final long serialVersionUID = 1L;

	public BoardNotAuthorizedException() {
		super("Not authorized to perform this action on the board");
	}

	public BoardNotAuthorizedException(String msg) {
		super(msg);
	}
}
