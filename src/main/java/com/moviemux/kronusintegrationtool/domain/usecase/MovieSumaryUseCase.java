package com.moviemux.kronusintegrationtool.domain.usecase;

import com.moviemux.kronusintegrationtool.domain.model.MovieSummary;

public interface MovieSumaryUseCase {

  MovieSummary execute(Long tmdbId);
}
