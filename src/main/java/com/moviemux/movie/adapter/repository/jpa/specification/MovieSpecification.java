package com.moviemux.movie.adapter.repository.jpa.specification;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity;

public interface MovieSpecification {

	Page<MovieEntity> findMovieFilteredCustom(List<String> titles, List<Long> genres, String sortJoin, Pageable pageable);

}
