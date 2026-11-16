package com.moviemux.movie.domain.repository;

import com.moviemux.movie.domain.model.MovieStatisticsSnapshot.Film;
import com.moviemux.movie.domain.model.MovieStatisticsSnapshot.Genre;
import java.util.List;

public interface MovieStatisticsReader {
  List<Film> movies();

  List<Integer> ratings();

  List<Genre> genres();
}
