package com.moviemux.movie.adapter.repository.jpa;

import com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Primary
public interface MovieJpaRepository extends JpaRepository<MovieEntity, UUID>, JpaSpecificationExecutor<MovieEntity> {

	@Transactional(readOnly = true)
	MovieEntity findByTmdbId(Long tmdbId);

	Page<MovieEntity> findAll(Pageable pageable);

}
