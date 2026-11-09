package com.moviemux.movie.adapter.repository.rest.mapper;

import com.moviemux.movie.domain.MovieNote;
import com.moviemux.movie.domain.model.NotificationNote;
import com.moviemux.movie.adapter.repository.rest.dto.MovieNoteRestRequestDto;
import com.moviemux.movie.adapter.repository.rest.dto.MovieNoteUserRestRequestDto;

public final class NotificationMapper {
    private NotificationMapper() { }
    public static NotificationNote snapshot(MovieNote note) {
        return note == null ? null : new NotificationNote(note.getNote(), note.getCreatedAt(), note.getUpdatedAt(),
                note.getUser().getId(), note.getUser().getName());
    }
    public static MovieNoteRestRequestDto toRequest(NotificationNote note) {
        return new MovieNoteRestRequestDto(note.note(), note.createdAt(), note.updatedAt(),
                new MovieNoteUserRestRequestDto(note.userId(), note.userName()));
    }
}
