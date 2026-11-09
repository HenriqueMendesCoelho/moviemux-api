package com.moviemux.movie.domain.usecase;

import com.moviemux.movie.domain.exception.*;
import com.moviemux.movie.domain.model.*;
import java.util.*;

public interface GetMovieUseCase {
  Movie execute(UUID id, UUID userId) throws MovieNoteNotFoundException;
}
