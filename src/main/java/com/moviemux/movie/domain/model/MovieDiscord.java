package com.moviemux.movie.domain.model;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MovieDiscord {

  private UUID id;

  private String messageId;

  private Movie movie;
}
