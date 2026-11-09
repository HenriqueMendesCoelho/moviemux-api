package com.moviemux.movie.adapter.repository.postgres.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity(name = "MovieGenre")
@Table(name = "movie_genre")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MovieGenreEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column
  private Long id;

  @Column(nullable = false, unique = true)
  private String name;

  @Column(nullable = true, unique = true)
  private Long tmdbId;

  @ManyToMany
  @JoinTable(
      name = "movies_genres",
      joinColumns = @JoinColumn(name = "genre_id"),
      inverseJoinColumns = @JoinColumn(name = "movie_id"))
  private List<MovieEntity> movies;
}
