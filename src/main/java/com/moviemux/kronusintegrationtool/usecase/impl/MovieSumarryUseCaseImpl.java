package com.moviemux.kronusintegrationtool.usecase.impl;

import com.moviemux.kronusintegrationtool.domain.MovieSummary;
import com.moviemux.kronusintegrationtool.usecase.MovieSumaryUseCase;
import org.springframework.stereotype.Component;

import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;

@Component
public class MovieSumarryUseCaseImpl implements MovieSumaryUseCase {

	private final KronusCatalogRepository repository;

    public MovieSumarryUseCaseImpl(KronusCatalogRepository repository) {
        this.repository = repository;
    }

	@Override
	public MovieSummary execute(Long tmdbId) {
		return repository.movieSummary(tmdbId);
	}

}
