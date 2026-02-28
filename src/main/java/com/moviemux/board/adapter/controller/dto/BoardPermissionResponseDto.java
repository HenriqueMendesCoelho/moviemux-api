package com.moviemux.board.adapter.controller.dto;

import java.util.UUID;

import com.moviemux.board.domain.BoardPermission;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BoardPermissionResponseDto {

	private UUID publicId;
	private UUID userPublicId;
	private String userName;
	private String permission;

	public BoardPermissionResponseDto(BoardPermission boardPermission) {
		publicId = boardPermission.getPublicId();
		userPublicId = boardPermission.getUser().getId();
		userName = boardPermission.getUser().getName();
		permission = boardPermission.getPermission().name();
	}
}
