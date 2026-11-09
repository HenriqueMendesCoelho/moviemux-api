package com.moviemux.kronusintegrationtool.domain.usecase;

import com.moviemux.kronusintegrationtool.domain.model.WatchProviders;

public interface SearchWatchProviedersUseCase {

  WatchProviders execute(Long movieId);
}
