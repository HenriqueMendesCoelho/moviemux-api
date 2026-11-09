package com.moviemux.movie.adapter.repository.postgres.entity;

import com.moviemux.user.adapter.repository.postgres.entity.UserEntity;
import jakarta.persistence.*;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Comparator;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity(name = "MovieNote")
@Table(name = "movie_note")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MovieNoteEntity implements Serializable, Cloneable {
  private static final long serialVersionUID = -3877745486636189677L;

  @EmbeddedId private MovieNoteKeyEntity id;

  @Column private Integer note;

  @CreationTimestamp
  @Column(nullable = false, columnDefinition = "timestamp with time zone")
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(columnDefinition = "timestamp with time zone")
  private OffsetDateTime updatedAt;

  @ManyToOne
  @MapsId("userId")
  @JoinColumn(name = "user_id")
  private UserEntity user;

  @ManyToOne
  @MapsId("movieId")
  @JoinColumn(name = "movie_id")
  private MovieEntity movie;

  @Builder
  public MovieNoteEntity(
      Integer note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      UserEntity user,
      MovieEntity movie) {
    this.id = new MovieNoteKeyEntity(user.getId(), movie.getId());
    this.note = note;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.user = user;
    this.movie = movie;
  }

  public static Comparator<MovieNoteEntity> comparator() {
    return Comparator.comparingInt((MovieNoteEntity m) -> m.getNote() != null ? m.getNote() : 0)
        .reversed()
        .thenComparing((m) -> m.getUser().getName());
  }

  public static Comparator<MovieNoteEntity> comparatorAlphabetical() {
    return Comparator.comparing((m) -> m.getUser().getName());
  }

  @Override
  public MovieNoteEntity clone() throws CloneNotSupportedException {
    return (MovieNoteEntity) super.clone();
  }
}
