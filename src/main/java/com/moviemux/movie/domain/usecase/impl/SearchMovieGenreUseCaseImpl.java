package com.moviemux.movie.domain.usecase.impl;
import com.moviemux.movie.domain.model.MovieGenre;
import com.moviemux.movie.domain.repository.MovieGenreRepository;
import com.moviemux.movie.domain.usecase.SearchMovieGenreUseCase;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.util.List;
@Component @RequiredArgsConstructor
public class SearchMovieGenreUseCaseImpl implements SearchMovieGenreUseCase {
 private final MovieGenreRepository repository;
 public List<MovieGenre> execute(){return repository.findAllByOrderByNameAsc();}
}
