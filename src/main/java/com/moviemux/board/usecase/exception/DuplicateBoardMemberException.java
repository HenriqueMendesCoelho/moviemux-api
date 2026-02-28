package com.moviemux.board.usecase.exception;

public class DuplicateBoardMemberException extends ReflectiveOperationException {

	private static final long serialVersionUID = 1L;

	public DuplicateBoardMemberException() {
		super("User is already a member or has a pending invitation");
	}

	public DuplicateBoardMemberException(String msg) {
		super(msg);
	}
}
