package com.kronusboss.cine.adapter.movie.controller.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import com.kronusboss.cine.movie.domain.MovieNote;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MovieNoteResponseDto {
	
	private UUID id;
	private int note;
	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;
	private MovieNoteUserResponseDto user;
	
	public MovieNoteResponseDto(MovieNote movieNote) {
		id = movieNote.getId();
		note = movieNote.getNote();
		createdAt = movieNote.getCreatedAt();
		updatedAt = movieNote.getUpdatedAt();
		user = new MovieNoteUserResponseDto(movieNote.getUser());
	}
	
}