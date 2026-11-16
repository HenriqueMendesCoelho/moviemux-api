package com.moviemux.user.domain.command;

import com.moviemux.user.domain.model.User;
import java.util.UUID;

public record UpdateAccountCommand(User user, UUID userId, String emailUserLoged) {}
