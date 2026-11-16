package com.moviemux.user.domain.command;

public record FindUserCommand(String targetEmail, String authenticatedEmail) {}
