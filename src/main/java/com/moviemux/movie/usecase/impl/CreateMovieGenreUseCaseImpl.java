package com.moviemux.movie.usecase.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.moviemux.kronusintegrationtool.adapter.repository.rest.KronusIntegrationToolRepository;
import com.moviemux.movie.adapter.repository.MovieGenreRepository;
import com.moviemux.movie.domain.MovieGenre;
import com.moviemux.movie.usecase.CreateMovieGenreUseCase;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class CreateMovieGenreUseCaseImpl implements CreateMovieGenreUseCase {

	@Autowired
	private KronusIntegrationToolRepository kronusIntegrationToolRepository;

	@Autowired
	private MovieGenreRepository repository;

	@Scheduled(initialDelay = 5000, fixedDelayString = "P15D")
	@Override
	public void scheduled() {
		List<MovieGenre> genres = kronusIntegrationToolRepository.listGenres().stream()
                .map(genre -> MovieGenre.builder().id(genre.id()).tmdbId(genre.id()).name(genre.name()).build())
                .toList();

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
