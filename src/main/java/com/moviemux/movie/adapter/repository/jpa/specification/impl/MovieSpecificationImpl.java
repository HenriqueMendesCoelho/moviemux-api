package com.moviemux.movie.adapter.repository.jpa.specification.impl;

import com.moviemux.movie.adapter.repository.jpa.MovieJpaRepository;
import com.moviemux.movie.adapter.repository.jpa.specification.MovieSpecification;
import com.moviemux.movie.domain.Movie;
import com.moviemux.movie.domain.MovieGenre;
import com.moviemux.movie.domain.MovieNote;
import jakarta.persistence.criteria.*;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MovieSpecificationImpl implements MovieSpecification {

	private final MovieJpaRepository repository;

	@Override
	public Page<Movie> findMovieFilteredCustom(List<String> titles, List<Long> genres, String sortJoin,
			Pageable pageable) {
		Pageable _pageable = pageable;
		if (StringUtils.isNotEmpty(sortJoin) && ("notes,asc".equalsIgnoreCase(sortJoin)
				|| "notes,desc".equalsIgnoreCase(sortJoin))) {
			_pageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
		}

		return repository.findAll(filterMovies(titles, genres, sortJoin, pageable), _pageable);
	}

	private Specification<Movie> filterMovies(List<String> titles, List<Long> genres, String sortJoin,
			Pageable pageable) {
		return (Root<Movie> root, CriteriaQuery<?> query, CriteriaBuilder criteriaBuilder) -> {
			Predicate predicate = criteriaBuilder.conjunction();
			Sort defaultSort = pageable.getSort();
			List<org.springframework.data.domain.Sort.Order> defaultOrders =
					defaultSort != null ? defaultSort.toList() : Collections.emptyList();

			if (CollectionUtils.isNotEmpty(titles)) {
				List<Predicate> titlePredicates = new ArrayList<>();
				for (String title : titles) {
					if (StringUtils.isNotEmpty(title)) {
						titlePredicates.add(criteriaBuilder.or(
								criteriaBuilder.like(criteriaBuilder.lower(root.get("portugueseTitle")),
										"%" + title.toLowerCase() + "%"),
								criteriaBuilder.like(criteriaBuilder.lower(root.get("englishTitle")),
										"%" + title.toLowerCase() + "%"),
								criteriaBuilder.like(criteriaBuilder.lower(root.get("originalTitle")),
										"%" + title.toLowerCase() + "%")));
					}
				}

				predicate = criteriaBuilder.and(predicate,
						criteriaBuilder.and(titlePredicates.toArray(new Predicate[0])));
			}

			if (StringUtils.isNotEmpty(sortJoin) || CollectionUtils.isNotEmpty(genres)) {
				query.groupBy(root.get("id"));
			}

			if (CollectionUtils.isNotEmpty(genres)) {
				Subquery<UUID> subquery = query.subquery(UUID.class);
				Root<Movie> subMovieRoot = subquery.from(Movie.class);
				Join<Movie, MovieGenre> genreJoin = subMovieRoot.join("genres");
				subquery.select(subMovieRoot.get("id"));

				Predicate genrePredicate = genreJoin.get("id").in(genres);
				subquery.where(genrePredicate);
				subquery.groupBy(subMovieRoot.get("id"));
				subquery.having(criteriaBuilder.equal(criteriaBuilder.count(genreJoin), genres.size()));

				Predicate hasAllGenres = criteriaBuilder.in(root.get("id")).value(subquery);
				predicate = criteriaBuilder.and(predicate, hasAllGenres);
			}

			if (StringUtils.isNotEmpty(sortJoin)) {
				Join<Movie, MovieNote> movieNoteJoin = root.join("notes", JoinType.LEFT);
				List<Order> orders = new ArrayList<>();

				if ("notes,asc".equalsIgnoreCase(sortJoin)) {
					orders.add(criteriaBuilder.asc(criteriaBuilder.avg(movieNoteJoin.get("note"))));
				}
				if ("notes,desc".equalsIgnoreCase(sortJoin)) {
					orders.add(criteriaBuilder.desc(criteriaBuilder.avg(movieNoteJoin.get("note"))));
				}
				if (!defaultOrders.isEmpty()) {
					defaultOrders.forEach(order -> {
						Path<?> expression = root.get(order.getProperty());
						Order newOrder = order.isAscending()
								? criteriaBuilder.asc(expression)
								: criteriaBuilder.desc(expression);
						orders.add(newOrder);
					});
				}

				query.orderBy(orders);
			}

			return predicate;
		};
	}

}
