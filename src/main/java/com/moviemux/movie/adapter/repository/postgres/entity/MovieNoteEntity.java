package com.moviemux.movie.adapter.repository.postgres.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;
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

  @Column(name = "user_id", insertable = false, updatable = false)
  private UUID userId;

  @ManyToOne
  @MapsId("movieId")
  @JoinColumn(name = "movie_id")
  private MovieEntity movie;

  @Builder
  public MovieNoteEntity(
      Integer note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      UUID userId,
      MovieEntity movie) {
    this.id = new MovieNoteKeyEntity(userId, movie.getId());
    this.note = note;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.userId = userId;
    this.movie = movie;
  }

  @Override
  public MovieNoteEntity clone() throws CloneNotSupportedException {
    return (MovieNoteEntity) super.clone();
  }
}
