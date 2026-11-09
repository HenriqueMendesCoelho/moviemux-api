package com.moviemux.movie.adapter.repository.postgres.entity;


import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity(name = "MovieDiscord")
@Table(name = "movie_discord")
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MovieDiscordEntity {

  @Id @Column private UUID id;

  @Column(unique = true)
  private String messageId;

  @JsonIgnore
  @OneToOne
  @MapsId
  @JoinColumn(name = "movie_id", referencedColumnName = "id")
  private MovieEntity movie;
}
