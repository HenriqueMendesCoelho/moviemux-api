package com.moviemux.kronusintegrationtool.adapter.repository.rest.dto;

import java.util.List;

import com.moviemux.kronusintegrationtool.domain.Credit;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class CreditResponseDto {

	List<CastResponseDto> cast;
	List<CastResponseDto> crew;

	@Getter
	@Setter
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

		public Credit.Cast toDomain() {
            return com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper.CatalogResponseMapper.cast(this);
		}

	}

	public Credit toDomain() {
        return com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper.CatalogResponseMapper.credits(this);
	}

}
