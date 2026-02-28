package com.moviemux.movie.usecase.impl;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.moviemux.movie.adapter.repository.MovieGenreRepository;
import com.moviemux.movie.domain.MovieGenre;
import com.moviemux.movie.usecase.SearchMovieGenreUseCase;

@Component
@RequiredArgsConstructor
public class SearchMovieGenreUseCaseImpl implements SearchMovieGenreUseCase {

	private final MovieGenreRepository repository;

	@Override
	public List<MovieGenre> list() {
		return repository.findAllByOrderByNameAsc();
	}

	@Override
	public List<MovieGenre> listAllGenresWithMovies() {
		return repository.findAllGenresHaveMovieAssociated();
	}

}
