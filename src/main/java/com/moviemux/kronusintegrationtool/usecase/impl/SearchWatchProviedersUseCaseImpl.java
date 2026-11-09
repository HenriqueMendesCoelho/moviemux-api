package com.moviemux.kronusintegrationtool.usecase.impl;

import com.moviemux.kronusintegrationtool.domain.WatchProviders;
import org.springframework.stereotype.Component;

import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import com.moviemux.kronusintegrationtool.usecase.SearchWatchProviedersUseCase;

@Component
public class SearchWatchProviedersUseCaseImpl implements SearchWatchProviedersUseCase {

	private final KronusCatalogRepository repository;

    public SearchWatchProviedersUseCaseImpl(KronusCatalogRepository repository) {
        this.repository = repository;
    }

	@Override
	public WatchProviders searchByMovieId(Long movieId) {
		return repository.getWatchProviders(movieId);
	}

}
