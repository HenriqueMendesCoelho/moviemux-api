package com.moviemux.movie.adapter.repository.postgres;

import com.moviemux.movie.adapter.repository.postgres.mapper.MovieEntityMapper;
import com.moviemux.movie.domain.model.*;
import com.moviemux.movie.domain.repository.MovieRepository;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MoviePostgresRepository implements MovieRepository {
  private final com.moviemux.movie.adapter.repository.MovieRepository repository;
  private final EntityManager entities;

  public MoviePostgresRepository(
      com.moviemux.movie.adapter.repository.MovieRepository repository, EntityManager entities) {
    this.repository = repository;
    this.entities = entities;
  }

  @Transactional(readOnly = true)
  public Optional<Movie> findById(UUID id) {
    return repository.findById(id).map(MovieEntityMapper::movie);
  }

  @Transactional(readOnly = true)
  public Movie findByTmdbId(Long id) {
    return MovieEntityMapper.movie(repository.findByTmdbId(id));
  }

  @Transactional
  public Movie save(Movie model) {
    var entity =
        model.getId() == null
            ? new com.moviemux.movie.domain.Movie()
            : repository.findById(model.getId()).orElseGet(com.moviemux.movie.domain.Movie::new);
    MovieEntityMapper.copyScalars(model, entity);
    entity.setUser(
        model.getUser() == null
            ? null
            : entities.getReference(com.moviemux.user.domain.User.class, model.getUser().id()));
    if (model.getGenres() != null)
      entity.setGenres(
          model.getGenres().stream()
              .map(
                  g -> entities.getReference(com.moviemux.movie.domain.MovieGenre.class, g.getId()))
              .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
    return MovieEntityMapper.movie(repository.saveAndFlush(entity));
  }

  @Transactional
  public void delete(Movie model) {
    repository.findById(model.getId()).ifPresent(repository::delete);
  }

  @Transactional(readOnly = true)
  public MoviePage findFiltered(List<String> titles, List<Long> genres, MovieQuery query) {
    var page =
        repository.findMovieFilteredCustom(titles, genres, query.sortJoin(), pageable(query));
    return new MoviePage(
        page.getContent().stream().map(MovieEntityMapper::movie).toList(),
        page.getTotalElements(),
        query);
  }

  public static Pageable pageable(MovieQuery query) {
    Sort sort =
        Sort.by(
            query.orders().stream()
                .map(
                    o -> {
                      Sort.Order result =
                          new Sort.Order(
                              o.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC,
                              o.property(),
                              Sort.NullHandling.valueOf(o.nullHandling().name()));
                      return o.ignoreCase() ? result.ignoreCase() : result;
                    })
                .toList());
    return query.unpaged()
        ? Pageable.unpaged(sort)
        : PageRequest.of(query.page(), query.size(), sort);
  }
}
