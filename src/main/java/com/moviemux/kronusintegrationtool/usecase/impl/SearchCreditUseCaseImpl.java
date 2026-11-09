package com.moviemux.kronusintegrationtool.usecase.impl;

import com.moviemux.kronusintegrationtool.domain.Credit;
import com.moviemux.kronusintegrationtool.usecase.SearchCreditUseCase;
import org.springframework.stereotype.Component;

import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;

@Component
public class SearchCreditUseCaseImpl implements SearchCreditUseCase {

	private final KronusCatalogRepository repository;

    public SearchCreditUseCaseImpl(KronusCatalogRepository repository) {
        this.repository = repository;
    }

	@Override
	public Credit getMovieCredits(Long movieId) {
		return repository.getCredits(movieId);
	}

}
