package com.moviemux.movie.adapter.repository.jpa.specification.impl;

import java.util.List;

import com.moviemux.movie.adapter.repository.jpa.MovieGenreJpaRepository;
import com.moviemux.movie.adapter.repository.jpa.specification.MovieGenreSpecification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity;
import com.moviemux.movie.adapter.repository.postgres.entity.MovieGenreEntity;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;

@Repository
public class MovieGenreSpecificationImpl implements MovieGenreSpecification {

	@Autowired
	private MovieGenreJpaRepository repository;

	@Override
	public List<MovieGenreEntity> findAllGenresHaveMovieAssociated() {
		return repository.findAll(filterMovieGenreWithMovie());
	}

	private Specification<MovieGenreEntity> filterMovieGenreWithMovie() {
		return (Root<MovieGenreEntity> root, CriteriaQuery<?> query, CriteriaBuilder criteriaBuilder) -> {
			Join<MovieGenreEntity, MovieEntity> movieJoin = root.join("movies", JoinType.LEFT);
			query.orderBy(criteriaBuilder.asc(root.get("name")));

			return criteriaBuilder.and(criteriaBuilder.isNotNull(movieJoin));
		};
	}
}
