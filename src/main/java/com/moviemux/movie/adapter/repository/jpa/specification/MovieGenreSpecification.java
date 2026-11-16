package com.moviemux.movie.adapter.repository.jpa.specification;

import java.util.List;

import com.moviemux.movie.adapter.repository.postgres.entity.MovieGenreEntity;

public interface MovieGenreSpecification {

	List<MovieGenreEntity> findAllGenresHaveMovieAssociated();

}
