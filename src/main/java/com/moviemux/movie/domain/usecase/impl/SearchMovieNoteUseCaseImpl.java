package com.moviemux.movie.domain.usecase.impl;
import com.moviemux.movie.domain.model.*;
import com.moviemux.movie.domain.repository.MovieRepository;
import com.moviemux.movie.domain.usecase.SearchMovieNoteUseCase;
import com.moviemux.movie.domain.exception.MovieNotFoundException;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;
import java.util.*;
@Component @RequiredArgsConstructor
public class SearchMovieNoteUseCaseImpl implements SearchMovieNoteUseCase {
 private final MovieRepository repository;
 public List<MovieNote> execute(UUID movieId,UUID userId) throws MovieNotFoundException {
  Movie movie=repository.findById(movieId).orElseThrow(MovieNotFoundException::new);
  var notes=MovieVisibility.project(movie,userId,false).getNotes();
  notes.sort(MovieNote.comparator());return notes;
 }
}
