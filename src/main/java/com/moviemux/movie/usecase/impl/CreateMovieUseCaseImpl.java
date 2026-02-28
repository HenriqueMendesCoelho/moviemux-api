package com.moviemux.movie.usecase.impl;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;

import com.moviemux.discord.usecase.SendMessageWebhookUseCase;
import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.adapter.repository.rest.MovieSocketRespository;
import com.moviemux.movie.domain.Movie;
import com.moviemux.movie.usecase.CreateMovieUseCase;
import com.moviemux.movie.usecase.exception.DuplicatedMovieException;
import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.domain.User;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateMovieUseCaseImpl implements CreateMovieUseCase {

	private final MovieRepository repository;
	private final UserRepository userRepository;
	private final SendMessageWebhookUseCase sendMessageWebhook;
	private final MovieSocketRespository socketRepository;

	@Override
	@CacheEvict(value = "statistics", allEntries = true)
	public Movie save(Movie movie, UUID userId) throws DuplicatedMovieException {

		if (repository.findByTmdbId(movie.getTmdbId()) != null) {
			throw new DuplicatedMovieException("This tmdb id is already registered");
		}

		User user = userRepository.findById(userId).orElse(null);
		movie.setUser(user);
		movie.setShowNotes(false);
		userRepository.saveAndFlush(user);
		Movie movieCreated = repository.saveAndFlush(movie);
		sendMessageDiscord(movieCreated.getId());
		emitEventSocket();

		return movieCreated;
	}

	private void sendMessageDiscord(UUID movieId) {
		try {
			sendMessageWebhook.sendMovieMessage(movieId);
		} catch (Exception e) {
			log.error("Error to send message on discord raised: ", e);
		}
	}

	private void emitEventSocket() {
		try {
			socketRepository.emitAllMoviesEvent(null);
		} catch (Exception e) {
			log.error("Error to emit event on movie creation raised: ", e);
		}
	}
}
