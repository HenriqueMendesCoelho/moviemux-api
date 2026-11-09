package com.moviemux.kronusintegrationtool.domain.usecase;

import com.moviemux.kronusintegrationtool.domain.model.MovieSearch;
import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SimilarMoviesUseCase {
  private final KronusCatalogRepository repository;

  public MovieSearch execute(Long movieId, Integer page) {
    return repository.moviesSimilar(movieId, page);
  }
}
