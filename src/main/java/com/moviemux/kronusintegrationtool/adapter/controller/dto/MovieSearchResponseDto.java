package com.moviemux.kronusintegrationtool.adapter.controller.dto;

import java.util.List;
import java.util.stream.Collectors;

import com.moviemux.kronusintegrationtool.domain.model.MovieSearch;

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
public class MovieSearchResponseDto {

	private Integer page;
	private List<ResultSearchResponseDto> results;
	private Integer totalPages;
	private Integer totalResults;

	public MovieSearchResponseDto(MovieSearch movieSearch) {
		page = movieSearch.getPage();
		results = movieSearch.getResults().stream().map(ResultSearchResponseDto::new).collect(Collectors.toList());
		totalPages = movieSearch.getTotalPages();
		totalResults = movieSearch.getTotalResults();
	}
}
