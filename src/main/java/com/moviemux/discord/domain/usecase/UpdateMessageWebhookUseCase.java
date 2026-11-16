package com.moviemux.discord.domain.usecase;

import java.util.UUID;

public interface UpdateMessageWebhookUseCase {
  void execute(UUID movieId);
}
