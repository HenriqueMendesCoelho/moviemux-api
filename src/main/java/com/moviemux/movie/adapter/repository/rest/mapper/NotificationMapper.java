package com.moviemux.movie.adapter.repository.rest.mapper;

import com.moviemux.movie.adapter.repository.rest.dto.MovieNoteRestRequestDto;
import com.moviemux.movie.adapter.repository.rest.dto.MovieNoteUserRestRequestDto;
import com.moviemux.movie.domain.model.NotificationNote;

public final class NotificationMapper {
  private NotificationMapper() {}

  public static MovieNoteRestRequestDto toRequest(NotificationNote note) {
    return new MovieNoteRestRequestDto(
        note.note(),
        note.createdAt(),
        note.updatedAt(),
        new MovieNoteUserRestRequestDto(note.userId(), note.userName()));
  }
}
