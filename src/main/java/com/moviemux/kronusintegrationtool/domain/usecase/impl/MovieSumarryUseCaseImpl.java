package com.moviemux.kronusintegrationtool.domain.usecase.impl;

import com.moviemux.kronusintegrationtool.domain.model.MovieSummary;
import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import com.moviemux.kronusintegrationtool.domain.usecase.MovieSumaryUseCase;
import org.springframework.stereotype.Component;

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
