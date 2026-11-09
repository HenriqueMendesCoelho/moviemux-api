package com.moviemux.movie.domain.exception;

public class DuplicatedMovieException extends ReflectiveOperationException {

	private static final long serialVersionUID = 4087604245442641655L;

	public DuplicatedMovieException() {
		super("Movie duplicated");
	}

	public DuplicatedMovieException(String msg) {
		super(msg);
	}
}
