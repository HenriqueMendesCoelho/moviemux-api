package com.moviemux.statistic.domain.model;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MovieStatistic {

  private Integer totalNumberOfMovies;
  private Double averageRate;
  private Integer averageRuntime;
  private Map<String, Integer> moviesByGender;
  private Map<String, Long> moviesSixMonthsAgo;
}
