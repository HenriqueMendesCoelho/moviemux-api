package com.moviemux.movie.adapter.repository.postgres.entity;

import com.moviemux.user.adapter.repository.postgres.entity.UserEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrimaryKeyJoinColumn;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity(name = "Movie")
@Table(name = "movie")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MovieEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  @Column
  private UUID id;

  @Column(length = 150, nullable = false)
  private String portugueseTitle;

  @Column(length = 150, nullable = false)
  private String englishTitle;

  @Column(length = 150, nullable = true)
  private String originalTitle;

  @Column(length = 150, nullable = false)
  private String director;

  @Column(columnDefinition = "text")
  private String urlImage;

  @Column(columnDefinition = "text")
  private String portugueseUrlTrailer;

  @Column(columnDefinition = "text")
  private String englishUrlTrailer;

  @Column(columnDefinition = "text", nullable = false)
  private String description;

  @Column private LocalDate releaseDate;

  @Column(nullable = true, unique = true)
  private Long tmdbId;

  @Column(nullable = true, unique = true)
  private String imdbId;

  @Column(nullable = true)
  private Integer runtime;

  @Column private boolean showNotes;

  @OneToMany(mappedBy = "movie", cascade = CascadeType.ALL)
  private List<MovieNoteEntity> notes;

  @ManyToMany
  @JoinTable(
      name = "movies_genres",
      joinColumns = @JoinColumn(name = "movie_id"),
      inverseJoinColumns = @JoinColumn(name = "genre_id"))
  private List<MovieGenreEntity> genres;

  @ManyToOne(optional = true)
  @JoinColumn(name = "user_id", referencedColumnName = "id", nullable = true)
  private UserEntity user;

  @CreationTimestamp
  @Column(nullable = false, columnDefinition = "timestamp with time zone")
  private OffsetDateTime createdAt;

  @UpdateTimestamp
  @Column(columnDefinition = "timestamp with time zone")
  private OffsetDateTime updatedAt;

  @OneToOne(mappedBy = "movie", cascade = CascadeType.ALL)
  @PrimaryKeyJoinColumn
  private MovieDiscordEntity movieDiscord;
}
