package com.moviemux.discord.domain.usecase.impl;

import com.moviemux.discord.domain.repository.DiscordMovieRepository;
import com.moviemux.discord.domain.repository.WebhookRepository;
import com.moviemux.discord.domain.usecase.SendMessageWebhookUseCase;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SendMessageWebhookUseCaseImpl implements SendMessageWebhookUseCase {
  private final WebhookRepository repository;
  private final DiscordMovieRepository movieRepository;

  public SendMessageWebhookUseCaseImpl(
      WebhookRepository repository, DiscordMovieRepository movieRepository) {
    this.repository = repository;
    this.movieRepository = movieRepository;
  }

  @Override
  public void execute(UUID movieId) {
    var target = movieRepository.find(movieId).orElse(null);
    if (target == null) return;
    var result = repository.send(target);
    if (result.info() == null) return;
    target.saveMessageReference(result.info().getMessageId());
  }
}
