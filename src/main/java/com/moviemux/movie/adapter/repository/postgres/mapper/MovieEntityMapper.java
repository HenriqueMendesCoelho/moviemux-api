package com.moviemux.movie.adapter.repository.postgres.mapper;

import com.moviemux.movie.domain.model.*;
import java.util.*;

/** Every conversion returns detached models, never Hibernate-managed objects. */
public final class MovieEntityMapper {
  private MovieEntityMapper() {}

  public static MovieActor actor(
      UUID id, com.moviemux.user.domain.repository.UserIdentityLookup identities) {
    return id == null ? null : identities.find(id).map(MovieActor::from).orElse(null);
  }

  public static Movie shallow(
      com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity source,
      com.moviemux.user.domain.repository.UserIdentityLookup identities) {
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
    result.setUser(actor(source.getUserId(), identities));
    return result;
  }

  public static Movie movie(
      com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity source,
      com.moviemux.user.domain.repository.UserIdentityLookup identities) {
    Movie result = shallow(source, identities);
    if (result == null) return null;
    if (source.getGenres() != null)
      result.setGenres(
          source.getGenres().stream()
              .map(MovieEntityMapper::genre)
              .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
    if (source.getNotes() != null)
      result.setNotes(
          source.getNotes().stream()
              .map(note -> note(note, identities))
              .collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
    if (source.getMovieDiscord() != null)
      result.setMovieDiscord(
          new MovieDiscord(
              source.getMovieDiscord().getId(),
              source.getMovieDiscord().getMessageId(),
              shallow(source, identities)));
    return result;
  }

  public static MovieGenre genre(
      com.moviemux.movie.adapter.repository.postgres.entity.MovieGenreEntity source) {
    return source == null
        ? null
        : MovieGenre.builder()
            .id(source.getId())
            .name(source.getName())
            .tmdbId(source.getTmdbId())
            .build();
  }

  public static MovieNote note(
      com.moviemux.movie.adapter.repository.postgres.entity.MovieNoteEntity source,
      com.moviemux.user.domain.repository.UserIdentityLookup identities) {
    if (source == null) return null;
    return new MovieNote(
        new MovieNoteKey(source.getId().getUserId(), source.getId().getMovieId()),
        source.getNote(),
        source.getCreatedAt(),
        source.getUpdatedAt(),
        actor(source.getUserId(), identities),
        shallow(source.getMovie(), identities));
  }

  public static void copyScalars(
      Movie source, com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity result) {
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
