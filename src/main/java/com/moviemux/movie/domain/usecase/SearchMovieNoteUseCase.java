package com.moviemux.movie.domain.usecase;

import com.moviemux.movie.domain.exception.*;
import com.moviemux.movie.domain.model.*;
import java.util.*;

public interface SearchMovieNoteUseCase {
  List<MovieNote> execute(UUID movieId, UUID userId) throws MovieNotFoundException;
}
