package com.moviemux.movie.adapter.repository.postgres;

import com.moviemux.movie.adapter.repository.postgres.mapper.MovieEntityMapper;
import com.moviemux.movie.domain.model.*;
import com.moviemux.movie.domain.repository.MovieNoteRepository;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MovieNotePostgresRepository implements MovieNoteRepository {
  private final com.moviemux.movie.adapter.repository.MovieNoteRepository repository;
  private final EntityManager entities;

  public MovieNotePostgresRepository(
      com.moviemux.movie.adapter.repository.MovieNoteRepository repository,
      EntityManager entities) {
    this.repository = repository;
    this.entities = entities;
  }

  private static com.moviemux.movie.adapter.repository.postgres.entity.MovieNoteKeyEntity key(MovieNoteKey key) {
    return new com.moviemux.movie.adapter.repository.postgres.entity.MovieNoteKeyEntity(key.getUserId(), key.getMovieId());
  }

  @Transactional(readOnly = true)
  public Optional<MovieNote> findById(MovieNoteKey key) {
    return repository.findById(key(key)).map(MovieEntityMapper::note);
  }

  @Transactional
  public MovieNote save(MovieNote model) {
    var entity =
        repository.findById(key(model.getId())).orElseGet(com.moviemux.movie.adapter.repository.postgres.entity.MovieNoteEntity::new);
    entity.setId(key(model.getId()));
    entity.setNote(model.getNote());
    entity.setCreatedAt(model.getCreatedAt());
    entity.setUpdatedAt(model.getUpdatedAt());
    entity.setUser(
        entities.getReference(com.moviemux.user.adapter.repository.postgres.entity.UserEntity.class, model.getUser().id()));
    entity.setMovie(
        entities.getReference(com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity.class, model.getMovie().getId()));
    return MovieEntityMapper.note(repository.saveAndFlush(entity));
  }

  @Transactional
  public void delete(MovieNote note) {
    repository.findById(key(note.getId())).ifPresent(repository::delete);
  }
}
