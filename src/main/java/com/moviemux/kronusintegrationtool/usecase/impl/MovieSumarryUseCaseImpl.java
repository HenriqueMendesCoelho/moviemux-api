package com.moviemux.kronusintegrationtool.usecase.impl;

import com.moviemux.kronusintegrationtool.adapter.repository.rest.KronusIntegrationToolRepository;
import com.moviemux.kronusintegrationtool.domain.MovieSummary;
import com.moviemux.kronusintegrationtool.usecase.MovieSumaryUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MovieSumarryUseCaseImpl implements MovieSumaryUseCase {

	private final KronusIntegrationToolRepository repository;

	@Override
	public MovieSummary execute(Long tmdbId) {
		return repository.movieSummary(tmdbId);
	}

}
