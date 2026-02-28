package com.moviemux.board.adapter.controller.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moviemux.board.domain.Board;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BoardResponseDto {

	private UUID publicId;
	private String name;
	private UUID ownerPublicId;
	private String ownerName;
	private OffsetDateTime createdAt;
	private List<BoardMemberResponseDto> members;
	private List<BoardPermissionResponseDto> permissions;
	private List<BoardMovieResponseDto> movies;

	public BoardResponseDto(Board board) {
		publicId = board.getPublicId();
		name = board.getName();
		ownerPublicId = board.getOwner().getId();
		ownerName = board.getOwner().getName();
		createdAt = board.getCreatedAt();
		if (board.getMembers() != null) {
			members = board.getMembers().stream()
					.map(BoardMemberResponseDto::new)
					.collect(Collectors.toList());
		}
		if (board.getPermissions() != null) {
			permissions = board.getPermissions().stream()
					.map(BoardPermissionResponseDto::new)
					.collect(Collectors.toList());
		}
		if (board.getMovies() != null) {
			movies = board.getMovies().stream()
					.map(BoardMovieResponseDto::new)
					.collect(Collectors.toList());
		}
	}
}
