package com.moviemux.user.domain.command;

import com.moviemux.user.domain.model.User;

public record RegisterUserCommand(User user, String inviteCode) {}
