package com.moviemux.board.adapter.controller.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moviemux.board.domain.BoardMovie;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class BoardMovieResponseDto {

	private UUID publicId;
	private UUID movieId;
	private String moviePortugueseTitle;
	private String movieEnglishTitle;
	private String movieUrlImage;
	private UUID addedByPublicId;
	private String addedByName;
	private OffsetDateTime addedAt;
	private List<BoardMovieRatingResponseDto> ratings;

	public BoardMovieResponseDto(BoardMovie boardMovie) {
		publicId = boardMovie.getPublicId();
		movieId = boardMovie.getMovie().getId();
		moviePortugueseTitle = boardMovie.getMovie().getPortugueseTitle();
		movieEnglishTitle = boardMovie.getMovie().getEnglishTitle();
		movieUrlImage = boardMovie.getMovie().getUrlImage();
		if (boardMovie.getAddedBy() != null) {
			addedByPublicId = boardMovie.getAddedBy().getId();
			addedByName = boardMovie.getAddedBy().getName();
		}
		addedAt = boardMovie.getAddedAt();
		if (boardMovie.getRatings() != null) {
			ratings = boardMovie.getRatings().stream()
					.map(BoardMovieRatingResponseDto::new)
					.collect(Collectors.toList());
		}
	}
}
