package com.moviemux.kronusintegrationtool.adapter.controller.dto;

import java.time.LocalDate;
import java.util.List;

import com.moviemux.kronusintegrationtool.domain.model.MovieSummary;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovieSummaryResponseDto {

	private Long tmdbId;
	private String imdbId;
	private String portugueseTitle;
	private String englishTitle;
	private String originalTitle;
	private String director;
	private String urlImagePortuguese;
	private String urlImageEnglish;
	private String portugueseUrlTrailer;
	private String englishUrlTrailer;
	private String description;
	private List<String> genres;
	private LocalDate releaseDate;
	private Integer runtime;

	public MovieSummaryResponseDto(MovieSummary summary) {
		tmdbId = summary.getTmdbId();
		imdbId = summary.getImdbId();
		portugueseTitle = summary.getPortugueseTitle();
		englishTitle = summary.getEnglishTitle();
		originalTitle = summary.getOriginalTitle();
		director = summary.getDirector();
		urlImagePortuguese = summary.getUrlImagePortuguese();
		urlImageEnglish = summary.getUrlImageEnglish();
		portugueseUrlTrailer = summary.getPortugueseUrlTrailer();
		englishUrlTrailer = summary.getEnglishUrlTrailer();
		description = summary.getDescription();
		genres = summary.getGenres();
		releaseDate = summary.getReleaseDate();
		runtime = summary.getRuntime();
	}

}
