package com.moviemux.movie.adapter.repository.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import com.moviemux.movie.adapter.repository.postgres.entity.MovieNoteEntity;
import com.moviemux.movie.adapter.repository.postgres.entity.MovieNoteKeyEntity;

public interface MovieNoteJpaRepository extends JpaRepository<MovieNoteEntity, MovieNoteKeyEntity> {

}
