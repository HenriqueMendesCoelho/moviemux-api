package com.moviemux.movie.domain.repository;
import com.moviemux.movie.domain.model.*;
import java.util.Optional;
public interface MovieNoteRepository {
 Optional<MovieNote> findById(MovieNoteKey key);
 MovieNote save(MovieNote note);
 void delete(MovieNote note);
}
