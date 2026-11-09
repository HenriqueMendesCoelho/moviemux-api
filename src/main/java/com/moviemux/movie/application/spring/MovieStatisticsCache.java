package com.moviemux.movie.application.spring;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;

@Component
public class MovieStatisticsCache {
  @CacheEvict(value = "statistics", allEntries = true)
  public void invalidate() {}
}
