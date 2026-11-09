package com.moviemux.movie.domain.model;

import java.io.Serializable;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = { "userId", "movieId" })
public class MovieNoteKey implements Serializable {
	private static final long serialVersionUID = 3416178085752264747L;


	private UUID userId;


	private UUID movieId;

}
