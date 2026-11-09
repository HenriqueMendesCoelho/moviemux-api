package com.moviemux.kronusintegrationtool.adapter.repository.rest.dto;

import com.moviemux.kronusintegrationtool.domain.model.CatalogGenre;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class MovieGenreResponseDto {

	private Long id;
	private String name;

	public CatalogGenre toDomain() {
        return com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper.CatalogResponseMapper.genre(this);
	}
}
