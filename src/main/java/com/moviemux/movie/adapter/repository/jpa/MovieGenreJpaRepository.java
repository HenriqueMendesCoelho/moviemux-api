package com.moviemux.movie.adapter.repository.jpa;

import java.util.List;

import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.transaction.annotation.Transactional;

import com.moviemux.movie.adapter.repository.postgres.entity.MovieGenreEntity;

@Primary
public interface MovieGenreJpaRepository extends JpaRepository<MovieGenreEntity, Long>, JpaSpecificationExecutor<MovieGenreEntity> {

	@Transactional(readOnly = true)
	MovieGenreEntity findByName(String name);

	@Transactional(readOnly = true)
	List<MovieGenreEntity> findAllByOrderByNameAsc();
}
