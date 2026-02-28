package com.moviemux.movie.usecase.impl;

import com.moviemux.discord.usecase.UpdateMessageWebhookUseCase;
import com.moviemux.movie.adapter.repository.MovieNoteRepository;
import com.moviemux.movie.adapter.repository.rest.MovieSocketRespository;
import com.moviemux.movie.domain.Movie;
import com.moviemux.movie.domain.MovieNote;
import com.moviemux.movie.domain.MovieNoteKey;
import com.moviemux.movie.usecase.UpdateMovieNoteUseCase;
import com.moviemux.movie.usecase.exception.MovieNoteNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateMovieNoteUseCaseImpl implements UpdateMovieNoteUseCase {

	private final MovieNoteRepository repository;
	private final UpdateMessageWebhookUseCase updateMessageWebhookUseCase;
	private final MovieSocketRespository movieSocketRespository;

	@Override
	@CacheEvict(value = "statistics", allEntries = true)
	public MovieNote update(UUID userId, UUID movieId, Integer note) throws MovieNoteNotFoundException {
		MovieNote movieNoteToUpdate = repository.findById(new MovieNoteKey(userId, movieId)).orElse(null);

		if (movieNoteToUpdate == null) {
			throw new MovieNoteNotFoundException();
		}

		movieNoteToUpdate.setNote(note);

		MovieNote noteUpdated = repository.saveAndFlush(movieNoteToUpdate);
		updateMessageWebhookUseCase.updateMovieMessage(movieNoteToUpdate.getMovie().getId());
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

			movieSocketRespository.emitEventMovie(movie.getId(), "update-note", eventContent, null);
		} catch (Exception e) {
			log.error("Error to emit event on note update (UpdateMovieNoteUseCaseImpl): ", e);
		}

	}

}
