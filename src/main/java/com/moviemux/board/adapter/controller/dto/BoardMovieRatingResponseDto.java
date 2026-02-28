package com.moviemux.board.adapter.controller.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.moviemux.board.domain.BoardMovieRating;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BoardMovieRatingResponseDto {

	private UUID publicId;
	private UUID userPublicId;
	private String userName;
	private Integer rating;
	private OffsetDateTime createdAt;
	private OffsetDateTime updatedAt;

	public BoardMovieRatingResponseDto(BoardMovieRating boardMovieRating) {
		publicId = boardMovieRating.getPublicId();
		userPublicId = boardMovieRating.getUser().getId();
		userName = boardMovieRating.getUser().getName();
		rating = boardMovieRating.getRating();
		createdAt = boardMovieRating.getCreatedAt();
		updatedAt = boardMovieRating.getUpdatedAt();
	}
}
