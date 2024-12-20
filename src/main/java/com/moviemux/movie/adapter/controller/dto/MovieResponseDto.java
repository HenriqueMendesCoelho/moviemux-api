package com.moviemux.movie.adapter.controller.dto;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moviemux.movie.domain.Movie;

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

	public MovieResponseDto(Movie movie) {
		id = movie.getId();
		portugueseTitle = movie.getPortugueseTitle();
		englishTitle = movie.getEnglishTitle();
		originalTitle = movie.getOriginalTitle();
		director = movie.getDirector();
		urlImage = movie.getUrlImage();
		portugueseUrlTrailer = movie.getPortugueseUrlTrailer();
		englishUrlTrailer = movie.getEnglishUrlTrailer();
		description = movie.getDescription();
		releaseDate = movie.getReleaseDate();
		tmdbId = movie.getTmdbId();
		if (movie.getNotes() != null) {
			notes = movie.getNotes().stream().map(MovieNoteResponseDto::new).collect(Collectors.toList());
		}
		genres = movie.getGenres().stream().map(MovieGenreResponseDto::new).collect(Collectors.toList());
		imdbId = movie.getImdbId();
		if (movie.getUser() != null) {
			userId = movie.getUser().getId();
			userName = movie.getUser().getName();
		}

		runtime = movie.getRuntime();
		createdAt = movie.getCreatedAt();
		updatedAt = movie.getUpdatedAt();
		showNotes = movie.isShowNotes();
		Duration duration = Duration.between(movie.getCreatedAt(), OffsetDateTime.now());
		timeSinceCreation = duration.getSeconds();
	}
}
