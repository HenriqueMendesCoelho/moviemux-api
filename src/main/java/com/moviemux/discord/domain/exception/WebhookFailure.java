package com.moviemux.discord.domain.exception;

public final class WebhookFailure extends RuntimeException {
    public WebhookFailure(Throwable cause) { super("External integration failed", cause); }
}
