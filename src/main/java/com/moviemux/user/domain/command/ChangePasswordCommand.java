package com.moviemux.user.domain.command;

import java.util.UUID;

public record ChangePasswordCommand(UUID userId, String password, String newPassword) {}
