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
public class MovieSummary {

  private Long tmdbId;
  private String imdbId;
  private String portugueseTitle;
  private String englishTitle;
  private String originalTitle;
  private String director;
  private String urlImagePortuguese;
  private String urlImageEnglish;
  private String portugueseUrlTrailer;
  private String englishUrlTrailer;
  private String description;
  private List<String> genres;
  private LocalDate releaseDate;
  private Integer runtime;
}
