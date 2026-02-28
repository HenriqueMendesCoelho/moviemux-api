package com.moviemux.kronusintegrationtool.usecase.impl;

import com.moviemux.kronusintegrationtool.adapter.repository.rest.KronusIntegrationToolRepository;
import com.moviemux.kronusintegrationtool.domain.Credit;
import com.moviemux.kronusintegrationtool.usecase.SearchCreditUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchCreditUseCaseImpl implements SearchCreditUseCase {

	private final KronusIntegrationToolRepository repository;

	@Override
	public Credit getMovieCredits(Long movieId) {
		return repository.getCredits(movieId);
	}

}
