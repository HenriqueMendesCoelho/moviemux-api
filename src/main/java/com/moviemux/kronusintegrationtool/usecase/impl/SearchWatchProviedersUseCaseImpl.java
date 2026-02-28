package com.moviemux.kronusintegrationtool.usecase.impl;

import com.moviemux.kronusintegrationtool.adapter.repository.rest.KronusIntegrationToolRepository;
import com.moviemux.kronusintegrationtool.domain.WatchProviders;
import com.moviemux.kronusintegrationtool.usecase.SearchWatchProviedersUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchWatchProviedersUseCaseImpl implements SearchWatchProviedersUseCase {

	private final KronusIntegrationToolRepository repository;

	@Override
	public WatchProviders searchByMovieId(Long movieId) {
		return repository.getWatchProviders(movieId);
	}

}
