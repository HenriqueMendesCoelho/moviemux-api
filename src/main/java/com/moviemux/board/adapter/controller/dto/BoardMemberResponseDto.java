package com.moviemux.board.adapter.controller.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.moviemux.board.domain.BoardMember;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BoardMemberResponseDto {

	private UUID publicId;
	private UUID userPublicId;
	private String userName;
	private String status;
	private OffsetDateTime joinedAt;

	public BoardMemberResponseDto(BoardMember member) {
		publicId = member.getPublicId();
		userPublicId = member.getUser().getId();
		userName = member.getUser().getName();
		status = member.getStatus().name();
		joinedAt = member.getJoinedAt();
	}
}
