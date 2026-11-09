package com.moviemux.movie.domain.usecase.impl;
import com.moviemux.movie.domain.command.CreateMovieNoteCommand;

import com.moviemux.movie.domain.repository.MovieAnnouncement;
import com.moviemux.movie.domain.repository.MovieNoteRepository;
import com.moviemux.movie.domain.repository.MovieRepository;
import com.moviemux.movie.domain.repository.MovieNotificationRepository;
import com.moviemux.movie.domain.model.NotificationNote;
import com.moviemux.movie.domain.model.Movie;
import com.moviemux.movie.domain.model.MovieNote;
import com.moviemux.movie.domain.model.MovieNoteKey;
import com.moviemux.movie.domain.usecase.CreateMovieNoteUseCase;
import com.moviemux.movie.domain.exception.DuplicatedMovieNoteException;
import com.moviemux.movie.domain.exception.MovieNotFoundException;
import com.moviemux.movie.domain.repository.MovieIdentityRepository;
import com.moviemux.movie.domain.model.MovieActor;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
@Transactional(rollbackFor = Exception.class)
public class CreateMovieNoteUseCaseImpl implements CreateMovieNoteUseCase {

	private final MovieNoteRepository repository;

	private final MovieRepository movieRepository;

	private final MovieIdentityRepository userRepository;

	private final MovieAnnouncement updateMessageWebhookUseCase;

	private final MovieNotificationRepository movieSocketRespository;

	@Override
	public MovieNote execute(CreateMovieNoteCommand command) throws MovieNotFoundException, DuplicatedMovieNoteException {
        UUID movieId=command.movieId(); Integer note=command.note(); String emailUserLoged=command.email();
		MovieActor user = userRepository.findByEmail(emailUserLoged);
		Movie movie = movieRepository.findById(movieId).orElse(null);

		if (movie == null) {
			throw new MovieNotFoundException();
		}

		MovieNote movieNoteExists = repository.findById(new MovieNoteKey(user.getId(), movie.getId())).orElse(null);

		if (movieNoteExists != null) {
			throw new DuplicatedMovieNoteException();
		}

		MovieNote movieNote = MovieNote.builder().movie(movie).note(note).user(user).build();
		userRepository.flush(user.getId());
		MovieNote noteCreated = repository.save(movieNote);
		updateMessageWebhookUseCase.updated(movie.getId());
		sendEventSocket(noteCreated);

		return noteCreated;
	}

	private void sendEventSocket(MovieNote note) {
		try {
			MovieNote eventContent = note.clone();
			Movie movie = note.getMovie();

			if (!movie.isShowNotes()) {
				eventContent.setNote(null);
			}

			movieSocketRespository.notifyMovie(movie.getId(), "create-note", new NotificationNote(eventContent.getNote(),eventContent.getCreatedAt(),eventContent.getUpdatedAt(),eventContent.getUser().getId(),eventContent.getUser().getName()), null);
		} catch (Exception e) {
			log.error("Error to emit event on note create (CreateMovieNoteUseCaseImpl): ", e);
		}

	}

}
