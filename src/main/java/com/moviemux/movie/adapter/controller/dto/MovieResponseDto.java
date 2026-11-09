package com.moviemux.movie.adapter.controller.dto;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MovieResponseDto {

  private UUID id;
  private String portugueseTitle;
  private String englishTitle;
  private String originalTitle;
  private String director;
  private String urlImage;
  private String portugueseUrlTrailer;
  private String englishUrlTrailer;
  private String description;
  private LocalDate releaseDate;
  private Long tmdbId;
  private String imdbId;
  private Integer runtime;
  private UUID userId;
  private String userName;
  List<MovieNoteResponseDto> notes;
  List<MovieGenreResponseDto> genres;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private boolean showNotes;
  private Long timeSinceCreation;
}
