package com.moviemux.movie.domain.usecase;
import com.moviemux.movie.domain.model.*;
import com.moviemux.movie.domain.exception.*;
import java.util.*;
public interface GetMovieUseCase { Movie execute(UUID id, UUID userId) throws MovieNoteNotFoundException; }
