package com.moviemux.movie.domain.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;



@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MovieGenre {




	private Long id;


	private String name;


	private Long tmdbId;

}
