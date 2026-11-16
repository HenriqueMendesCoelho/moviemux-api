package com.moviemux.movie.adapter.repository.postgres;

import com.moviemux.movie.adapter.repository.postgres.mapper.MovieEntityMapper;
import com.moviemux.movie.domain.model.MovieGenre;
import com.moviemux.movie.domain.repository.MovieGenreRepository;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MovieGenrePostgresRepository implements MovieGenreRepository {
  private final com.moviemux.movie.adapter.repository.MovieGenreRepository repository;

  public MovieGenrePostgresRepository(
      com.moviemux.movie.adapter.repository.MovieGenreRepository repository) {
    this.repository = repository;
  }

  @Transactional(readOnly = true)
  public MovieGenre findByName(String name) {
    return MovieEntityMapper.genre(repository.findByName(name));
  }

  @Transactional
  public MovieGenre save(MovieGenre genre) {
    var entity =
        genre.getId() == null
            ? new com.moviemux.movie.adapter.repository.postgres.entity.MovieGenreEntity()
            : repository
                .findById(genre.getId())
                .orElseGet(
                    com.moviemux.movie.adapter.repository.postgres.entity.MovieGenreEntity::new);
    entity.setId(genre.getId());
    entity.setName(genre.getName());
    entity.setTmdbId(genre.getTmdbId());
    return MovieEntityMapper.genre(repository.saveAndFlush(entity));
  }

  @Transactional(readOnly = true)
  public List<MovieGenre> findAllByOrderByNameAsc() {
    return repository.findAllByOrderByNameAsc().stream().map(MovieEntityMapper::genre).toList();
  }

  @Transactional(readOnly = true)
  public List<MovieGenre> findAllGenresHaveMovieAssociated() {
    return repository.findAllGenresHaveMovieAssociated().stream()
        .map(MovieEntityMapper::genre)
        .toList();
  }
}
