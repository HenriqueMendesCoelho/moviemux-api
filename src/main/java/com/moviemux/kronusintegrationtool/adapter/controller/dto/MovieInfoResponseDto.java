package com.moviemux.kronusintegrationtool.adapter.controller.dto;

import com.moviemux.kronusintegrationtool.domain.model.MovieSummary;
import org.apache.commons.lang3.StringUtils;

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
public class MovieInfoResponseDto {

	private String title;
	private String urlImage;
	private String description;

	public MovieInfoResponseDto(MovieSummary summary) {
		title = StringUtils.isNotBlank(summary.getPortugueseTitle()) ? summary.getPortugueseTitle()
				: summary.getEnglishTitle();
		urlImage = StringUtils.isNotBlank(summary.getUrlImagePortuguese()) ? summary.getUrlImagePortuguese()
				: summary.getUrlImageEnglish();
		description = summary.getDescription();
	}

}
