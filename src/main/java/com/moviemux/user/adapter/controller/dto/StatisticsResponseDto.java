package com.moviemux.user.adapter.controller.dto;

import com.moviemux.user.domain.model.Statistics;
import lombok.Data;

@Data
public class StatisticsResponseDto {

  private Integer ratingsGiven;
  private Integer registeredMovies;
  private Integer displayTime;
  private Double averageRatingMovies;

  public StatisticsResponseDto(Statistics statistics) {
    ratingsGiven = statistics.getRatingsGiven();
    registeredMovies = statistics.getRegisteredMovies();
    displayTime = statistics.getDisplayTime();
    averageRatingMovies = statistics.getAverageRatingMovies();
  }
}
