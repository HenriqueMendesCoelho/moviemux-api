package com.moviemux.user.adapter.controller.dto;

import com.moviemux.user.domain.model.Statistics;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class StatisticsResponseAdmDto {

  private Integer ratingsGiven;
  private Integer registeredMovies;
  private Integer consecutiveFailedLoginAttempts;
  private Integer displayTime;
  private Double averageRatingMovies;
  private OffsetDateTime lastLoginAt;

  public StatisticsResponseAdmDto(Statistics statistics) {
    ratingsGiven = statistics.getRatingsGiven();
    registeredMovies = statistics.getRegisteredMovies();
    consecutiveFailedLoginAttempts = statistics.getConsecutiveFailedLoginAttempts();
    displayTime = statistics.getDisplayTime();
    averageRatingMovies = statistics.getAverageRatingMovies();
    lastLoginAt = statistics.getLastLoginAt();
  }
}
