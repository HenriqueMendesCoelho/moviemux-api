package com.moviemux.board.usecase.exception;

public class BoardMemberNotFoundException extends ReflectiveOperationException {

	private static final long serialVersionUID = 1L;

	public BoardMemberNotFoundException() {
		super("Board member not found");
	}

	public BoardMemberNotFoundException(String msg) {
		super(msg);
	}
}
