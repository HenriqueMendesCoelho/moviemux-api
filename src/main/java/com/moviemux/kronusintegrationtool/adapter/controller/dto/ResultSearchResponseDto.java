package com.moviemux.kronusintegrationtool.adapter.controller.dto;

import java.time.LocalDate;
import java.util.List;

import com.moviemux.kronusintegrationtool.domain.model.ResultSearch;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ResultSearchResponseDto {

	private boolean adult;
	private String backdropPath;
	private List<Integer> genreIds;
	private Integer id;
	private String originalLanguage;
	private String originalTitle;
	private String overview;
	private String popularity;
	private String posterPath;
	private LocalDate releaseDate;
	private String title;
	private boolean video;
	private double voteAverage;
	private Integer voteCount;

	public ResultSearchResponseDto(ResultSearch resultSearch) {
		adult = resultSearch.isAdult();
		backdropPath = resultSearch.getBackdropPath();
		genreIds = resultSearch.getGenreIds();
		id = resultSearch.getId();
		originalLanguage = resultSearch.getOriginalLanguage();
		originalTitle = resultSearch.getOriginalTitle();
		overview = resultSearch.getOverview();
		popularity = resultSearch.getPopularity();
		posterPath = resultSearch.getPosterPath();
		releaseDate = resultSearch.getReleaseDate();
		title = resultSearch.getTitle();
		video = resultSearch.isVideo();
		voteAverage = resultSearch.getVoteAverage();
		voteCount = resultSearch.getVoteCount();
	}
}
