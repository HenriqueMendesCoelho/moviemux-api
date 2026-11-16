package com.moviemux.adapter.util.exception;

public class TokenInvalidException extends RuntimeException {

	private static final long serialVersionUID = -3928823663404742566L;

	public TokenInvalidException(String msg) {
		super(msg);
	}

	public TokenInvalidException() {
		super("token invalid");
	}
}
