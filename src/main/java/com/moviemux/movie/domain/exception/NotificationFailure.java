package com.moviemux.movie.domain.exception;

public final class NotificationFailure extends RuntimeException {
    public NotificationFailure(Throwable cause) { super("External integration failed", cause); }
}
