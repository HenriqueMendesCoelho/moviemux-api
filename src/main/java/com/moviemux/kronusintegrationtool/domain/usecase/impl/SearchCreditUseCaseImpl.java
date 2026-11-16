package com.moviemux.kronusintegrationtool.domain.usecase.impl;

import com.moviemux.kronusintegrationtool.domain.model.Credit;
import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import com.moviemux.kronusintegrationtool.domain.usecase.SearchCreditUseCase;
import org.springframework.stereotype.Component;

@Component
public class SearchCreditUseCaseImpl implements SearchCreditUseCase {

  private final KronusCatalogRepository repository;

  public SearchCreditUseCaseImpl(KronusCatalogRepository repository) {
    this.repository = repository;
  }

  @Override
  public Credit execute(Long movieId) {
    return repository.getCredits(movieId);
  }
}
