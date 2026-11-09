package com.moviemux.movie.domain.usecase.impl;

import com.moviemux.movie.domain.model.MovieGenre;
import com.moviemux.movie.domain.repository.MovieGenreRepository;
import com.moviemux.movie.domain.usecase.SearchMovieGenresWithMoviesUseCase;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchMovieGenresWithMoviesUseCaseImpl implements SearchMovieGenresWithMoviesUseCase {
  private final MovieGenreRepository repository;

  public List<MovieGenre> execute() {
    return repository.findAllGenresHaveMovieAssociated();
  }
}
