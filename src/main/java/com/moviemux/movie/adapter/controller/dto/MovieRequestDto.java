package com.moviemux.movie.adapter.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MovieRequestDto {

  @NotBlank private String portugueseTitle;

  @NotBlank private String englishTitle;

  private String originalTitle;

  @NotBlank private String director;

  @NotBlank private String urlImage;

  private String portugueseUrlTrailer;

  private String englishUrlTrailer;

  @NotBlank private String description;

  @NotNull private List<Long> genres;

  private LocalDate releaseDate;

  private Long tmdbId;

  private String imdbId;

  @NotNull private Integer runtime;

  private boolean showNotes;
}
