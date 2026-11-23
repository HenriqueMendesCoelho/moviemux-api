package com.moviemux.kronusintegrationtool.domain.repository;

import com.moviemux.kronusintegrationtool.domain.model.MovieSearch;

/** Scoped consumer's provider contract. The provider receives no actor or ranking identity. */
@FunctionalInterface
public interface KronusRankingMovieSearch {
  MovieSearch search(String query, int page, String language);
}
