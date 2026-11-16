package com.moviemux.user.domain.command;

import java.util.UUID;

public record DemoteUserCommand(UUID userId) {}
