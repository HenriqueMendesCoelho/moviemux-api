package com.moviemux.discord.usecase.impl;

import com.moviemux.discord.domain.repository.WebhookRepository;
import com.moviemux.discord.domain.repository.DiscordMovieRepository;
import com.moviemux.discord.usecase.UpdateMessageWebhookUseCase;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class UpdateMessageWebhookUseCaseImpl implements UpdateMessageWebhookUseCase {
    private final WebhookRepository repository;
    private final DiscordMovieRepository movieRepository;
    public UpdateMessageWebhookUseCaseImpl(WebhookRepository repository, DiscordMovieRepository movieRepository) {
        this.repository = repository;
        this.movieRepository = movieRepository;
    }
    @Override public void updateMovieMessage(UUID movieId) {
        var target = movieRepository.find(movieId).orElse(null);
        if (target == null) return;
        if (!target.showNotes()) return;
        repository.edit(target);
    }
}
