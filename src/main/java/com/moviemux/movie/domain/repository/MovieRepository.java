package com.moviemux.movie.domain.repository;
import com.moviemux.movie.domain.model.*;
import java.util.*;
public interface MovieRepository {
 Optional<Movie> findById(UUID id);
 Movie findByTmdbId(Long tmdbId);
 Movie save(Movie movie);
 void delete(Movie movie);
 MoviePage findFiltered(List<String> titles,List<Long> genres,MovieQuery query);
}
