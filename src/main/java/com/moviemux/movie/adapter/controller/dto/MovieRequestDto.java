package com.moviemux.movie.adapter.controller.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import com.moviemux.movie.domain.Movie;
import com.moviemux.movie.domain.MovieGenre;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MovieRequestDto {

	@NotBlank
	private String portugueseTitle;

	@NotBlank
	private String englishTitle;

	private String originalTitle;

	@NotBlank
	private String director;

	@NotBlank
	private String urlImage;

	private String portugueseUrlTrailer;

	private String englishUrlTrailer;

	@NotBlank
	private String description;

	@NotNull
	private List<Long> genres;

	private LocalDate releaseDate;

	private Long tmdbId;

	private String imdbId;

	@NotNull
	private Integer runtime;

	private boolean showNotes;

	public Movie toDomain() {
		return Movie.builder()
				.portugueseTitle(portugueseTitle)
				.englishTitle(englishTitle)
				.originalTitle(originalTitle)
				.director(director)
				.urlImage(urlImage)
				.portugueseUrlTrailer(portugueseUrlTrailer)
				.englishUrlTrailer(englishUrlTrailer)
				.description(description)
				.releaseDate(releaseDate)
				.tmdbId(tmdbId)
				.imdbId(imdbId)
				.runtime(runtime)
				.showNotes(showNotes)
				.genres(genres.stream().map(g -> MovieGenre.builder().id(g).build()).collect(Collectors.toList()))
				.build();
	}
}
