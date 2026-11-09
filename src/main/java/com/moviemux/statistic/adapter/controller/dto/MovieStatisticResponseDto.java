package com.moviemux.statistic.adapter.controller.dto;

import com.moviemux.statistic.domain.model.MovieStatistic;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MovieStatisticResponseDto {

  private Integer totalNumberOfMovies;
  private Double averageRate;
  private Integer averageRuntime;
  private Map<String, Integer> moviesByGender;
  private Map<String, Long> moviesSixMonthsAgo;

  public MovieStatisticResponseDto(MovieStatistic statistic) {
    totalNumberOfMovies = statistic.getTotalNumberOfMovies();
    averageRate = statistic.getAverageRate();
    averageRuntime = statistic.getAverageRuntime();
    moviesByGender = statistic.getMoviesByGender();
    moviesSixMonthsAgo = statistic.getMoviesSixMonthsAgo();
  }
}
