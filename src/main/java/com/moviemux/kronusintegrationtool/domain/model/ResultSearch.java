package com.moviemux.kronusintegrationtool.domain.model;

import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ResultSearch {

  private boolean adult;
  private String backdropPath;
  private List<Integer> genreIds;
  private Integer id;
  private String originalLanguage;
  private String originalTitle;
  private String overview;
  private String popularity;
  private String posterPath;
  private LocalDate releaseDate;
  private String title;
  private boolean video;
  private double voteAverage;
  private Integer voteCount;
}
