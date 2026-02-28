package com.moviemux.movie.usecase.impl;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.domain.Movie;
import com.moviemux.movie.domain.MovieNote;
import com.moviemux.movie.usecase.SearchMovieNoteUseCase;
import com.moviemux.movie.usecase.exception.MovieNotFoundException;

@Component
@RequiredArgsConstructor
public class SearchMovieNoteUseCaseImpl implements SearchMovieNoteUseCase {

	private final MovieRepository repository;

	@Override
	public List<MovieNote> list(UUID movieId, UUID userId) throws MovieNotFoundException {

		Movie movie = repository.findById(movieId).orElse(null);

		if (movie == null) {
			throw new MovieNotFoundException();
		}

		List<MovieNote> notes = movie.getNotes().stream().map(n -> {
			if (!movie.isShowNotes() && !n.getUser().getId().equals(userId)) {
				n.setNote(null);
			}

			return n;
		}).collect(Collectors.toList());
		notes.sort(MovieNote.comparator());

		return notes;
	}

}
