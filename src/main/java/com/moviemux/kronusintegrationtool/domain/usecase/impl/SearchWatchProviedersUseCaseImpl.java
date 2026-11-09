package com.moviemux.kronusintegrationtool.domain.usecase.impl;

import com.moviemux.kronusintegrationtool.domain.model.WatchProviders;
import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import com.moviemux.kronusintegrationtool.domain.usecase.SearchWatchProviedersUseCase;
import org.springframework.stereotype.Component;

@Component
public class SearchWatchProviedersUseCaseImpl implements SearchWatchProviedersUseCase {

  private final KronusCatalogRepository repository;

  public SearchWatchProviedersUseCaseImpl(KronusCatalogRepository repository) {
    this.repository = repository;
  }

  @Override
  public WatchProviders execute(Long movieId) {
    return repository.getWatchProviders(movieId);
  }
}
