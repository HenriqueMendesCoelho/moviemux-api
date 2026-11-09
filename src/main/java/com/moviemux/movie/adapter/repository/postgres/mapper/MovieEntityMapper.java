package com.moviemux.movie.adapter.repository.postgres.mapper;

import com.moviemux.movie.domain.model.*;
import com.moviemux.user.domain.model.Role;
import java.util.*;

/** Every conversion returns detached models, never Hibernate-managed objects. */
public final class MovieEntityMapper {
  private MovieEntityMapper() {}

  public static MovieActor actor(com.moviemux.user.adapter.repository.postgres.entity.UserEntity source) {
    return source == null
        ? null
        : new MovieActor(source.getId(), source.getName(), source.getEmail(), source.getRoles());
  }

  public static Movie shallow(com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity source) {
    if (source == null) return null;
    Movie result = new Movie();
    result.setId(source.getId());
    result.setPortugueseTitle(source.getPortugueseTitle());
    result.setEnglishTitle(source.getEnglishTitle());
    result.setOriginalTitle(source.getOriginalTitle());
    result.setDirector(source.getDirector());
    result.setUrlImage(source.getUrlImage());
    result.setPortugueseUrlTrailer(source.getPortugueseUrlTrailer());
    result.setEnglishUrlTrailer(source.getEnglishUrlTrailer());
    result.setDescription(source.getDescription());
    result.setReleaseDate(source.getReleaseDate());
    result.setTmdbId(source.getTmdbId());
    result.setImdbId(source.getImdbId());
    result.setRuntime(source.getRuntime());
    result.setShowNotes(source.isShowNotes());
    result.setCreatedAt(source.getCreatedAt());
    result.setUpdatedAt(source.getUpdatedAt());
    result.setUser(actor(source.getUser()));
    return result;
  }

  public static Movie movie(com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity source) {
    Movie result = shallow(source);
    if (result == null) return null;
    if (source.getGenres() != null)
      result.setGenres(
          source.getGenres().stream()
              .map(MovieEntityMapper::genre)
              .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
    if (source.getNotes() != null)
      result.setNotes(
          source.getNotes().stream()
              .map(MovieEntityMapper::note)
              .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
    if (source.getMovieDiscord() != null)
      result.setMovieDiscord(
          new MovieDiscord(
              source.getMovieDiscord().getId(),
              source.getMovieDiscord().getMessageId(),
              shallow(source)));
    return result;
  }

  public static MovieGenre genre(com.moviemux.movie.adapter.repository.postgres.entity.MovieGenreEntity source) {
    return source == null
        ? null
        : MovieGenre.builder()
            .id(source.getId())
            .name(source.getName())
            .tmdbId(source.getTmdbId())
            .build();
  }

  public static MovieNote note(com.moviemux.movie.adapter.repository.postgres.entity.MovieNoteEntity source) {
    if (source == null) return null;
    return new MovieNote(
        new MovieNoteKey(source.getId().getUserId(), source.getId().getMovieId()),
        source.getNote(),
        source.getCreatedAt(),
        source.getUpdatedAt(),
        actor(source.getUser()),
        shallow(source.getMovie()));
  }

  public static void copyScalars(Movie source, com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity result) {
    result.setId(source.getId());
    result.setPortugueseTitle(source.getPortugueseTitle());
    result.setEnglishTitle(source.getEnglishTitle());
    result.setOriginalTitle(source.getOriginalTitle());
    result.setDirector(source.getDirector());
    result.setUrlImage(source.getUrlImage());
    result.setPortugueseUrlTrailer(source.getPortugueseUrlTrailer());
    result.setEnglishUrlTrailer(source.getEnglishUrlTrailer());
    result.setDescription(source.getDescription());
    result.setReleaseDate(source.getReleaseDate());
    result.setTmdbId(source.getTmdbId());
    result.setImdbId(source.getImdbId());
    result.setRuntime(source.getRuntime());
    result.setShowNotes(source.isShowNotes());
    result.setCreatedAt(source.getCreatedAt());
    result.setUpdatedAt(source.getUpdatedAt());
  }
}
