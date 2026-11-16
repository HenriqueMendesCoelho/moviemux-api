package com.moviemux.kronusintegrationtool.domain.usecase;

import com.moviemux.kronusintegrationtool.domain.model.MovieSearch;
import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DiscoverMoviesUseCase {
  private final KronusCatalogRepository repository;

  public MovieSearch execute(
      String sortBy, Integer page, Integer year, String withGenres, String withoutGenres) {
    return repository.discoverMovies(sortBy, page, year, withGenres, withoutGenres);
  }
}
