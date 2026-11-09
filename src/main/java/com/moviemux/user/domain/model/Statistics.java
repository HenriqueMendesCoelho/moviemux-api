package com.moviemux.user.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class Statistics {
  private UUID id;
  private Integer ratingsGiven = 0,
      registeredMovies = 0,
      displayTime = 0,
      consecutiveFailedLoginAttempts = 0;
  private Double averageRatingMovies = 0d;
  private OffsetDateTime lastLoginAt;

  @Builder
  public Statistics(int ratingsGiven, int registeredMovies, User user) {
    this.ratingsGiven = ratingsGiven;
    this.registeredMovies = registeredMovies;
    this.id = user == null ? null : user.getId();
  }
}
