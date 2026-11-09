package com.moviemux.movie.domain.usecase;

import com.moviemux.movie.domain.exception.*;
import com.moviemux.movie.domain.model.*;
import java.util.*;

public interface SearchMovieUseCase {
  MoviePage execute(MovieQuery query, UUID userId);
}
