package com.moviemux.movie.adapter.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MovieGenreResponseDto {

  private Long id;
  private String name;
  private Long tmdbId;
}
