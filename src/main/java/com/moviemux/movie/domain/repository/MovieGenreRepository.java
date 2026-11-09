package com.moviemux.movie.domain.repository;
import com.moviemux.movie.domain.model.MovieGenre;
import java.util.List;
public interface MovieGenreRepository {
 MovieGenre findByName(String name);
 MovieGenre save(MovieGenre genre);
 List<MovieGenre> findAllByOrderByNameAsc();
 List<MovieGenre> findAllGenresHaveMovieAssociated();
}
