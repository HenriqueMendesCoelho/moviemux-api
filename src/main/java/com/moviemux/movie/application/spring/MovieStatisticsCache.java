package com.moviemux.movie.application.spring;

import com.moviemux.adapter.core.effects.AfterCommitEffects;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

@Component
public class MovieStatisticsCache {
  private final CacheManager caches;
  private final AfterCommitEffects effects;

  public MovieStatisticsCache(CacheManager caches, AfterCommitEffects effects) {
    this.caches = caches;
    this.effects = effects;
  }

  public void invalidate() {
    effects.submit(
        "statistics cache invalidation",
        () -> {
          var cache = caches.getCache("statistics");
          if (cache != null) {
            cache.clear();
          }
        });
  }
}
