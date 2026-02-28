package com.moviemux.movie.adapter.controller.impl;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moviemux.movie.usecase.exception.DuplicatedMovieException;
import com.moviemux.movie.usecase.exception.MovieNotFoundException;
import com.moviemux.movie.usecase.exception.MovieNoteNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;

import com.moviemux.adapter.core.controller.dto.UserTokenDto;
import com.moviemux.movie.adapter.controller.MovieController;
import com.moviemux.movie.adapter.controller.dto.MovieGenreResponseDto;
import com.moviemux.movie.adapter.controller.dto.MovieRequestDto;
import com.moviemux.movie.adapter.controller.dto.MovieResponseDto;
import com.moviemux.movie.domain.Movie;
import com.moviemux.movie.domain.MovieGenre;
import com.moviemux.movie.usecase.CreateMovieUseCase;
import com.moviemux.movie.usecase.DeleteMovieUseCase;
import com.moviemux.movie.usecase.SearchMovieGenreUseCase;
import com.moviemux.movie.usecase.SearchMovieUseCase;
import com.moviemux.movie.usecase.UpdateMovieUseCase;
import com.moviemux.user.usecase.exception.UserNotAuthorizedException;

@Controller
public class MovieControllerImpl implements MovieController {

	@Autowired
	private CreateMovieUseCase createMovieUseCase;

	@Autowired
	private SearchMovieUseCase searchMovieUseCase;

	@Autowired
	private UpdateMovieUseCase updateMovieUseCase;

	@Autowired
	private DeleteMovieUseCase deleteMovieUseCase;

	@Autowired
	private SearchMovieGenreUseCase searchMovieGenreUseCase;

	@Override
	public Page<MovieResponseDto> listAllMovies(String title, String genre, String sortJoin, Pageable pageable,
			UserTokenDto user) {
		Page<Movie> response = searchMovieUseCase.listAllMovies(title, genre, sortJoin, pageable, user.getId());
		return response.map(MovieResponseDto::new);
	}

	@Override
	public MovieResponseDto getById(UUID id, UserTokenDto user) throws MovieNoteNotFoundException {
		Movie response = searchMovieUseCase.getById(id, user.getId());
		return new MovieResponseDto(response);
	}

	@Override
	public MovieResponseDto save(MovieRequestDto movie, UserTokenDto user) throws DuplicatedMovieException {
		Movie response = createMovieUseCase.save(movie.toDomain(), user.getId());
		return new MovieResponseDto(response);
	}

	@Override
	public MovieResponseDto update(MovieRequestDto movie, UUID movieId, UserTokenDto user)
			throws MovieNotFoundException, UserNotAuthorizedException {
		Movie response = updateMovieUseCase.update(movie.toDomain(), movieId, user.getLogin());
		return new MovieResponseDto(response);
	}

	@Override
	public void delete(UUID id, UserTokenDto user) throws UserNotAuthorizedException {
		deleteMovieUseCase.delete(id, user.getId());
	}

	@Override
	public List<MovieGenreResponseDto> listGenres() {
		List<MovieGenre> genres = searchMovieGenreUseCase.list();
		return genres.stream().map(MovieGenreResponseDto::new).collect(Collectors.toList());
	}

	@Override
	public List<MovieGenreResponseDto> listAllGenresWithMovies() {
		List<MovieGenre> genres = searchMovieGenreUseCase.listAllGenresWithMovies();
		return genres.stream().map(MovieGenreResponseDto::new).collect(Collectors.toList());
	}

}
