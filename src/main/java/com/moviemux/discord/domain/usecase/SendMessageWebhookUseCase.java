package com.moviemux.discord.domain.usecase;

import java.util.UUID;

public interface SendMessageWebhookUseCase {
  void execute(UUID movieId);
}
