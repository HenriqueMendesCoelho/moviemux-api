package com.moviemux.movie.adapter.repository.postgres;

import com.moviemux.movie.adapter.repository.MovieGenreRepository;
import com.moviemux.movie.adapter.repository.MovieNoteRepository;
import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.domain.model.MovieStatisticsSnapshot.Film;
import com.moviemux.movie.domain.model.MovieStatisticsSnapshot.Genre;
import com.moviemux.movie.domain.repository.MovieStatisticsReader;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class MovieStatisticsReaderAdapter implements MovieStatisticsReader {
  private final MovieRepository movies;
  private final MovieNoteRepository notes;
  private final MovieGenreRepository genres;

  @Override
  @Transactional(readOnly = true)
  public List<Film> movies() {
    return movies.findAll().stream()
        .map(movie -> new Film(movie.getCreatedAt(), movie.getRuntime()))
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<Integer> ratings() {
    return notes.findAll().stream().map(note -> note.getNote()).toList();
  }

  @Override
  @Transactional(readOnly = true)
  public List<Genre> genres() {
    return genres.findAll().stream()
        .map(genre -> new Genre(genre.getName(), genre.getMovies().size()))
        .toList();
  }
}
