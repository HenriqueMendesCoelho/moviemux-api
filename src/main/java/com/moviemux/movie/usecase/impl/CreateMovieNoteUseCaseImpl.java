package com.moviemux.movie.usecase.impl;

import com.moviemux.discord.usecase.UpdateMessageWebhookUseCase;
import com.moviemux.movie.adapter.repository.MovieNoteRepository;
import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.adapter.repository.rest.MovieSocketRespository;
import com.moviemux.movie.domain.Movie;
import com.moviemux.movie.domain.MovieNote;
import com.moviemux.movie.domain.MovieNoteKey;
import com.moviemux.movie.usecase.CreateMovieNoteUseCase;
import com.moviemux.movie.usecase.exception.DuplicatedMovieNoteException;
import com.moviemux.movie.usecase.exception.MovieNotFoundException;
import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class CreateMovieNoteUseCaseImpl implements CreateMovieNoteUseCase {

	private final MovieNoteRepository repository;
	private final MovieRepository movieRepository;
	private final UserRepository userRepository;
	private final UpdateMessageWebhookUseCase updateMessageWebhookUseCase;
	private final MovieSocketRespository movieSocketRespository;

	@Override
	@CacheEvict(value = "statistics", allEntries = true)
	public MovieNote create(UUID movieId, Integer note, String emailUserLoged)
			throws MovieNotFoundException, DuplicatedMovieNoteException {
		User user = userRepository.findByEmail(emailUserLoged);
		Movie movie = movieRepository.findById(movieId).orElse(null);

		if (movie == null) {
			throw new MovieNotFoundException();
		}

		MovieNote movieNoteExists = repository.findById(new MovieNoteKey(user.getId(), movie.getId())).orElse(null);

		if (movieNoteExists != null) {
			throw new DuplicatedMovieNoteException();
		}

		MovieNote movieNote = MovieNote.builder().movie(movie).note(note).user(user).build();
		userRepository.saveAndFlush(user);
		MovieNote noteCreated = repository.saveAndFlush(movieNote);
		updateMessageWebhookUseCase.updateMovieMessage(movie.getId());
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

			movieSocketRespository.emitEventMovie(movie.getId(), "create-note", eventContent, null);
		} catch (Exception e) {
			log.error("Error to emit event on note create (CreateMovieNoteUseCaseImpl): ", e);
		}

	}

}
