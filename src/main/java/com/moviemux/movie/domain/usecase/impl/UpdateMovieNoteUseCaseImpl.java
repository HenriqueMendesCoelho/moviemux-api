package com.moviemux.movie.domain.usecase.impl;

import com.moviemux.movie.domain.command.UpdateMovieNoteCommand;
import com.moviemux.movie.domain.exception.MovieNoteNotFoundException;
import com.moviemux.movie.domain.model.Movie;
import com.moviemux.movie.domain.model.MovieNote;
import com.moviemux.movie.domain.model.MovieNoteKey;
import com.moviemux.movie.domain.model.NotificationNote;
import com.moviemux.movie.domain.repository.MovieAnnouncement;
import com.moviemux.movie.domain.repository.MovieNoteRepository;
import com.moviemux.movie.domain.repository.MovieNotificationRepository;
import com.moviemux.movie.domain.usecase.UpdateMovieNoteUseCase;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateMovieNoteUseCaseImpl implements UpdateMovieNoteUseCase {

  private final MovieNoteRepository repository;

  private final MovieAnnouncement updateMessageWebhookUseCase;

  private final MovieNotificationRepository movieSocketRespository;

  @Override
  public MovieNote execute(UpdateMovieNoteCommand command) throws MovieNoteNotFoundException {
    UUID userId = command.userId();
    UUID movieId = command.movieId();
    Integer note = command.note();
    MovieNote movieNoteToUpdate =
        repository.findById(new MovieNoteKey(userId, movieId)).orElse(null);

    if (movieNoteToUpdate == null) {
      throw new MovieNoteNotFoundException();
    }

    movieNoteToUpdate.setNote(note);

    MovieNote noteUpdated = repository.save(movieNoteToUpdate);
    updateMessageWebhookUseCase.updated(movieNoteToUpdate.getMovie().getId());
    sendEventSocket(noteUpdated);

    return noteUpdated;
  }

  private void sendEventSocket(MovieNote note) {
    try {
      MovieNote eventContent = note.clone();
      Movie movie = note.getMovie();

      if (!movie.isShowNotes()) {
        eventContent.setNote(null);
      }

      movieSocketRespository.notifyMovie(
          movie.getId(),
          "update-note",
          new NotificationNote(
              eventContent.getNote(),
              eventContent.getCreatedAt(),
              eventContent.getUpdatedAt(),
              eventContent.getUser().getId(),
              eventContent.getUser().getName()),
          null);
    } catch (Exception e) {
      log.error("Error to emit event on note update (UpdateMovieNoteUseCaseImpl): ", e);
    }
  }
}
