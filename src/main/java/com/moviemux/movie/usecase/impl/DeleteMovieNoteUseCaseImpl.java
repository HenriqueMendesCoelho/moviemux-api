package com.moviemux.movie.usecase.impl;

import com.moviemux.discord.usecase.UpdateMessageWebhookUseCase;
import com.moviemux.movie.adapter.repository.MovieNoteRepository;
import com.moviemux.movie.adapter.repository.rest.MovieSocketRespository;
import com.moviemux.movie.domain.Movie;
import com.moviemux.movie.domain.MovieNote;
import com.moviemux.movie.domain.MovieNoteKey;
import com.moviemux.movie.usecase.DeleteMovieNoteUseCase;
import com.moviemux.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteMovieNoteUseCaseImpl implements DeleteMovieNoteUseCase {

	private final MovieNoteRepository repository;
	private final UpdateMessageWebhookUseCase updateMessageWebhookUseCase;
	private final MovieSocketRespository movieSocketRespository;

	@Override
	@CacheEvict(value = "statistics", allEntries = true)
	public void delete(UUID userId, UUID movieId) {
		MovieNote movieNoteToDelete = repository.findById(new MovieNoteKey(userId, movieId)).orElse(null);

		if (movieNoteToDelete == null) {
			return;
		}

		repository.delete(movieNoteToDelete);
		updateMessageWebhookUseCase.updateMovieMessage(movieId);
		sendEventSocket(userId, movieId);
	}

	private void sendEventSocket(UUID userId, UUID movieId) {
		try {
			MovieNote eventContent = MovieNote.builder()
					.movie(Movie.builder().id(movieId).build())
					.user(User.builder().id(userId).build())
					.build();
			movieSocketRespository.emitEventMovie(movieId, "delete-note", eventContent, null);
		} catch (Exception e) {
			log.error("Error on Socket Api emit event delete note (DeleteMovieNoteUseCaseImpl):", e);
		}

	}
}
