package com.moviemux.movie.domain.usecase.impl;
import com.moviemux.movie.domain.model.*;
import com.moviemux.movie.domain.repository.MovieRepository;
import com.moviemux.movie.domain.usecase.SearchMovieUseCase;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.util.*;
@Component @RequiredArgsConstructor
public class SearchMovieUseCaseImpl implements SearchMovieUseCase {
 private final MovieRepository repository;
 public MoviePage execute(MovieQuery query,UUID userId){
  List<String> titles=query.title()==null||query.title().isBlank()?null:Arrays.asList(query.title().split(" "));
  List<Long> genres=query.genre()==null||query.genre().isBlank()?null:Arrays.stream(query.genre().split(",")).map(Long::parseLong).toList();
  MoviePage page=repository.findFiltered(titles,genres,query);
  return new MoviePage(page.content().stream().map(movie->MovieVisibility.project(movie,userId,false)).toList(),page.totalElements(),page.query());
 }
}
