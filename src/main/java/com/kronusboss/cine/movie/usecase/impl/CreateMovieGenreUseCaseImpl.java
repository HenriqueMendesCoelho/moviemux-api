package com.kronusboss.cine.movie.usecase.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.kronusboss.cine.kronusintegrationtool.adapter.repository.rest.KronusIntegrationToolRepository;
import com.kronusboss.cine.movie.adapter.repository.jpa.MovieGenreRepository;
import com.kronusboss.cine.movie.domain.MovieGenre;
import com.kronusboss.cine.movie.usecase.CreateMovieGenreUseCase;

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

		for (MovieGenre genre : genres) {
			MovieGenre exists = repository.findByName(genre.getName());

			if (exists == null) {
				continue;
			}
			count++;

			exists.setTmdbId(genre.getTmdbId());

			repository.saveAndFlush(exists);
		}

		log.info(String.format("Completed integration with TMDB of film genres, %s new genres registered", count));
	}

}
