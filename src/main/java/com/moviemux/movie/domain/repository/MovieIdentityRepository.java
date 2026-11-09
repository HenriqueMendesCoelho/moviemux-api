package com.moviemux.movie.domain.repository;
import com.moviemux.movie.domain.model.MovieActor;
import java.util.*;
public interface MovieIdentityRepository {
 Optional<MovieActor> findById(UUID id);
 MovieActor findByEmail(String email);
 /** Compatibility boundary preserving the legacy account flush and its failure ordering. */
 void flush(UUID id);
}
