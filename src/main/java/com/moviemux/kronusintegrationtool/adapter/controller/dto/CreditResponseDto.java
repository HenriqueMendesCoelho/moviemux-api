package com.moviemux.kronusintegrationtool.adapter.controller.dto;

import java.util.List;

import com.moviemux.kronusintegrationtool.domain.model.Credit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CreditResponseDto {

	List<CastResponseDto> cast;
	List<CastResponseDto> crew;

	@Getter
	@Setter
	@Builder
	@AllArgsConstructor
	@NoArgsConstructor
	public static class CastResponseDto {

		private String knownForDepartment;
		private String name;
		private String originalName;
		private String profilePath;
		private String character;
		private String department;
		private String job;

		public CastResponseDto(Credit.Cast cast) {
			knownForDepartment = cast.getKnownForDepartment();
			name = cast.getName();
			originalName = cast.getOriginalName();
			profilePath = cast.getProfilePath();
			character = cast.getCharacter();
			department = cast.getDepartment();
			job = cast.getJob();
		}

	}

	public CreditResponseDto(Credit credit) {
		cast = credit.getCast().stream().map(CastResponseDto::new).toList();
		crew = credit.getCrew().stream().map(CastResponseDto::new).toList();
	}

}
