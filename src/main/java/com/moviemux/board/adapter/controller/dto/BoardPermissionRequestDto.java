package com.moviemux.board.adapter.controller.dto;

import com.moviemux.board.domain.BoardPermissionType;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class BoardPermissionRequestDto {

	@NotNull
	private BoardPermissionType permission;

}
