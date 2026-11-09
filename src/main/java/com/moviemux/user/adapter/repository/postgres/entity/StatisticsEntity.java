package com.moviemux.user.adapter.repository.postgres.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.moviemux.movie.adapter.repository.postgres.entity.MovieNoteEntity;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;

@Entity(name = "Statistics")
@Table(name = "user_statistics")
@NoArgsConstructor
@Data
public class StatisticsEntity {

  @Id
  @Column(name = "user_id")
  private UUID id;

  @Transient private Integer ratingsGiven;

  @Transient private Integer registeredMovies;

  @Transient private Integer displayTime;

  @Transient private Double averageRatingMovies;

  @Column private Integer consecutiveFailedLoginAttempts;

  @Column(columnDefinition = "timestamp with time zone")
  private OffsetDateTime lastLoginAt;

  @JsonIgnore
  @OneToOne
  @MapsId
  @JoinColumn(name = "user_id", referencedColumnName = "id")
  private UserEntity user;

  @Builder
  public StatisticsEntity(int ratingsGiven, int registeredMovies, UserEntity user) {
    this.ratingsGiven = ratingsGiven;
    this.registeredMovies = registeredMovies;
    this.consecutiveFailedLoginAttempts = 0;
    this.user = user;
  }

  public Integer getRatingsGiven() {
    if (CollectionUtils.isNotEmpty(user.getNotes())) {
      return user.getNotes().size();
    }

    return 0;
  }

  public Integer getRegisteredMovies() {
    if (CollectionUtils.isNotEmpty(user.getMovies())) {
      return user.getMovies().size();
    }

    return 0;
  }

  public Integer getDisplayTime() {
    if (CollectionUtils.isNotEmpty(user.getNotes())) {
      return user.getNotes().stream().map(n -> n.getMovie().getRuntime()).mapToInt(m -> m).sum();
    }

    return 0;
  }

  public Double getAverageRatingMovies() {
    if (CollectionUtils.isNotEmpty(user.getNotes())) {
      return user.getNotes().stream()
          .map(MovieNoteEntity::getNote)
          .mapToDouble(m -> m)
          .average()
          .orElse(0);
    }

    return 0d;
  }
}
