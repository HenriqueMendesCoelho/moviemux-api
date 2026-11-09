package com.moviemux.movie.adapter.controller.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.validator.constraints.Range;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MovieNoteRequestDto {

  private UUID movieId;

  @Range(min = 0, max = 10)
  private Integer note;
}
