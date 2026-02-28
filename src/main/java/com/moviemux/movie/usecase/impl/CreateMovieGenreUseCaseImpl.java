package com.moviemux.movie.usecase.impl;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.moviemux.kronusintegrationtool.adapter.repository.rest.KronusIntegrationToolRepository;
import com.moviemux.movie.adapter.repository.MovieGenreRepository;
import com.moviemux.movie.domain.MovieGenre;
import com.moviemux.movie.usecase.CreateMovieGenreUseCase;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateMovieGenreUseCaseImpl implements CreateMovieGenreUseCase {

	private final KronusIntegrationToolRepository kronusIntegrationToolRepository;
	private final MovieGenreRepository repository;

	@Scheduled(initialDelay = 5000, fixedDelayString = "P15D")
	@Override
	public void scheduled() {
		List<MovieGenre> genres = kronusIntegrationToolRepository.listGenres();

		log.info("Started integration with TMDB of movie genres");

		int count = 0;

		for (MovieGenre genre : genres) {
			MovieGenre exists = repository.findByName(genre.getName());

			if (exists != null) {
				continue;
			}
			count++;
			repository.saveAndFlush(genre);
		}

		log.info(String.format("Completed integration with TMDB of film genres, %s new genres registered", count));
	}

}
