package com.moviemux.movie.adapter.repository.rest.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MovieNoteUserRestRequestDto {

  private UUID id;
  private String name;
}
