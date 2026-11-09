package com.moviemux.movie.domain.usecase.impl;
import com.moviemux.movie.domain.command.DeleteMovieNoteCommand;

import com.moviemux.movie.domain.repository.MovieAnnouncement;
import com.moviemux.movie.domain.repository.MovieNoteRepository;
import com.moviemux.movie.domain.repository.MovieNotificationRepository;
import com.moviemux.movie.domain.model.NotificationNote;
import com.moviemux.movie.domain.model.Movie;
import com.moviemux.movie.domain.model.MovieNote;
import com.moviemux.movie.domain.model.MovieNoteKey;
import com.moviemux.movie.domain.usecase.DeleteMovieNoteUseCase;
import com.moviemux.movie.domain.model.MovieActor;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class DeleteMovieNoteUseCaseImpl implements DeleteMovieNoteUseCase {

	private final MovieNoteRepository repository;

	private final MovieAnnouncement updateMessageWebhookUseCase;

	private final MovieNotificationRepository movieSocketRespository;

	@Override
	public void execute(DeleteMovieNoteCommand command) {
        UUID userId=command.userId(); UUID movieId=command.movieId();
		MovieNote movieNoteToDelete = repository.findById(new MovieNoteKey(userId, movieId)).orElse(null);

		if (movieNoteToDelete == null) {
			return;
		}

		repository.delete(movieNoteToDelete);
		updateMessageWebhookUseCase.updated(movieId);
		sendEventSocket(userId, movieId);
	}

	private void sendEventSocket(UUID userId, UUID movieId) {
		try {
			MovieNote eventContent = MovieNote.builder().movie(Movie.builder().id(movieId).build()).user(new MovieActor(userId,null,null,java.util.Set.of())).build();
			movieSocketRespository.notifyMovie(movieId, "delete-note", new NotificationNote(eventContent.getNote(),eventContent.getCreatedAt(),eventContent.getUpdatedAt(),eventContent.getUser().getId(),eventContent.getUser().getName()), null);
		} catch (Exception e) {
			log.error("Error on Socket Api emit event delete note (DeleteMovieNoteUseCaseImpl):", e);
		}

	}
}
