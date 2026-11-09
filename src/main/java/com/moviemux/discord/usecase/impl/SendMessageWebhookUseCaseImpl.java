package com.moviemux.discord.usecase.impl;

import com.moviemux.discord.domain.repository.WebhookRepository;
import com.moviemux.discord.domain.repository.DiscordMovieRepository;
import com.moviemux.discord.usecase.SendMessageWebhookUseCase;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class SendMessageWebhookUseCaseImpl implements SendMessageWebhookUseCase {
    private final WebhookRepository repository;
    private final DiscordMovieRepository movieRepository;
    public SendMessageWebhookUseCaseImpl(WebhookRepository repository, DiscordMovieRepository movieRepository) {
        this.repository = repository;
        this.movieRepository = movieRepository;
    }
    @Override public void sendMovieMessage(UUID movieId) {
        var target = movieRepository.find(movieId).orElse(null);
        if (target == null) return;
        var result = repository.send(target);
        if (result.info() == null) return;
        target.saveMessageReference(result.info().getMessageId());
    }
}
