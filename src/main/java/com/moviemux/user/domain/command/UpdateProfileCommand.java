package com.moviemux.user.domain.command;

import java.util.UUID;

public record UpdateProfileCommand(
    UUID userId, String name, String email, boolean notificationsEnabled) {}
