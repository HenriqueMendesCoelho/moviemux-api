package com.moviemux.movie.adapter.repository.postgres;

import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.domain.model.MovieCatalogEntry;
import com.moviemux.movie.domain.repository.MovieReadLookup;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MovieReadLookupAdapter implements MovieReadLookup {
  private final MovieRepository repository;

  public MovieReadLookupAdapter(MovieRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public List<MovieCatalogEntry> listAll() {
    return repository.findAll().stream()
        .map(
            movie ->
                new MovieCatalogEntry(movie.getId(), movie.getTmdbId(), movie.getPortugueseTitle()))
        .toList();
  }
}
