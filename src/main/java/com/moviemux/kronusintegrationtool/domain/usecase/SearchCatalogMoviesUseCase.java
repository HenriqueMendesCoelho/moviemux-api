package com.moviemux.kronusintegrationtool.domain.usecase;

import com.moviemux.kronusintegrationtool.domain.model.MovieSearch;
import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchCatalogMoviesUseCase {
  private final KronusCatalogRepository repository;

  public MovieSearch execute(String name, Integer page, String language) {
    return repository.searchByName(name, page, language, false);
  }
}
