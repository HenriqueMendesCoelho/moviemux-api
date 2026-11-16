package com.moviemux.statistic.application.spring;

import com.moviemux.statistic.domain.model.MovieStatistic;
import com.moviemux.statistic.domain.usecase.MovieStatisticsUseCase;
import com.moviemux.statistic.domain.usecase.impl.MovieStatisticsUseCaseImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
@RequiredArgsConstructor
public class CachedMovieStatistics implements MovieStatisticsUseCase {
  private final MovieStatisticsUseCaseImpl delegate;

  @Override
  @Cacheable("statistics")
  public MovieStatistic execute() {
    return delegate.execute();
  }
}
