package com.moviemux.movie.domain.model;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Comparator;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MovieNote implements Serializable, Cloneable {
  private static final long serialVersionUID = -3877745486636189677L;

  private MovieNoteKey id;

  private Integer note;

  private OffsetDateTime createdAt;

  private OffsetDateTime updatedAt;

  private MovieActor user;

  private Movie movie;

  @Builder
  public MovieNote(
      Integer note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      MovieActor user,
      Movie movie) {
    this.id = new MovieNoteKey(user.getId(), movie.getId());
    this.note = note;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.user = user;
    this.movie = movie;
  }

  public static Comparator<MovieNote> comparator() {
    return Comparator.comparingInt((MovieNote m) -> m.getNote() != null ? m.getNote() : 0)
        .reversed()
        .thenComparing((m) -> m.getUser().getName());
  }

  public static Comparator<MovieNote> comparatorAlphabetical() {
    return Comparator.comparing((m) -> m.getUser().getName());
  }

  @Override
  public MovieNote clone() throws CloneNotSupportedException {
    return (MovieNote) super.clone();
  }
}
