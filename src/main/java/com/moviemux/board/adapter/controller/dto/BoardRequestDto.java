package com.moviemux.board.adapter.controller.dto;

import com.moviemux.board.domain.Board;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class BoardRequestDto {

	@NotBlank
	@Size(min = 1, max = 100)
	private String name;

	public Board toDomain() {
		return Board.builder()
				.name(name)
				.build();
	}
}
