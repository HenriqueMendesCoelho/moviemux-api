package com.moviemux.application.spring.bridge;

import com.moviemux.discord.adapter.repository.rest.mapper.WebhookMovieMapper;
import com.moviemux.discord.domain.model.WebhookMovie;
import com.moviemux.discord.domain.repository.DiscordMovieRepository;
import com.moviemux.discord.domain.repository.MovieReferenceTarget;
import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.domain.MovieDiscord;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.UUID;

@Component
public class DiscordMovieBridge implements DiscordMovieRepository {
    private final MovieRepository repository;
    public DiscordMovieBridge(MovieRepository repository) { this.repository = repository; }

    @Override public Optional<MovieReferenceTarget> find(UUID id) {
        return repository.findById(id).map(movie -> new MovieReferenceTarget() {
            @Override public boolean showNotes() { return movie.isShowNotes(); }
            @Override public String messageId() {
                return movie.getMovieDiscord() == null ? null : movie.getMovieDiscord().getMessageId();
            }
            @Override public WebhookMovie snapshot() {
                return WebhookMovieMapper.snapshot(movie);
            }
            @Override public void saveMessageReference(String messageId) {
                movie.setMovieDiscord(new MovieDiscord(null, messageId, movie));
                repository.saveAndFlush(movie);
            }
        });
    }
}
