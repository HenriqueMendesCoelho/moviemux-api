package com.moviemux.movie.adapter.repository.rest.dto;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MovieNoteRestRequestDto {

  private Integer note;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private MovieNoteUserRestRequestDto user;
}
