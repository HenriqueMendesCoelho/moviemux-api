package com.moviemux.discord.usecase.impl;

import com.moviemux.discord.adapter.repository.rest.DiscordRepository;
import com.moviemux.discord.domain.DiscordWebhookInfo;
import com.moviemux.discord.usecase.SendMessageWebhookUseCase;
import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.domain.Movie;
import com.moviemux.movie.domain.MovieDiscord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SendMessageWebhookUseCaseImpl implements SendMessageWebhookUseCase {

	private final DiscordRepository repository;
	private final MovieRepository movieRepository;

	@Override
	public void sendMovieMessage(UUID movieId) {
		Movie movie = movieRepository.findById(movieId).orElse(null);

		if (movie == null) {
			return;
		}

		DiscordWebhookInfo info = repository.execute(movie);

		if (info == null) {
			return;
		}

		movie.setMovieDiscord(new MovieDiscord(null, info.getMessageId(), movie));
		movieRepository.saveAndFlush(movie);
	}

}
