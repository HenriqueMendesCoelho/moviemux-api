package com.moviemux.movie.adapter.controller.impl;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moviemux.movie.usecase.exception.DuplicatedMovieException;
import com.moviemux.movie.usecase.exception.DuplicatedMovieNoteException;
import com.moviemux.movie.usecase.exception.MovieNotFoundException;
import com.moviemux.movie.usecase.exception.MovieNoteNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;

import com.moviemux.core.adapter.controller.dto.UserTokenDto;
import com.moviemux.movie.adapter.controller.MovieController;
import com.moviemux.movie.adapter.controller.dto.MovieGenreResponseDto;
import com.moviemux.movie.adapter.controller.dto.MovieNoteRequestDto;
import com.moviemux.movie.adapter.controller.dto.MovieNoteResponseDto;
import com.moviemux.movie.adapter.controller.dto.MovieRequestDto;
import com.moviemux.movie.adapter.controller.dto.MovieResponseDto;
import com.moviemux.movie.domain.Movie;
import com.moviemux.movie.domain.MovieGenre;
import com.moviemux.movie.domain.MovieNote;
import com.moviemux.movie.usecase.CreateMovieNoteUseCase;
import com.moviemux.movie.usecase.CreateMovieUseCase;
import com.moviemux.movie.usecase.DeleteMovieNoteUseCase;
import com.moviemux.movie.usecase.DeleteMovieUseCase;
import com.moviemux.movie.usecase.SearchMovieGenreUseCase;
import com.moviemux.movie.usecase.SearchMovieNoteUseCase;
import com.moviemux.movie.usecase.SearchMovieUseCase;
import com.moviemux.movie.usecase.UpdateMovieNoteUseCase;
import com.moviemux.movie.usecase.UpdateMovieUseCase;
import com.moviemux.user.usecase.exception.UserNotAuthorizedException;

@Controller
@RequiredArgsConstructor
public class MovieControllerImpl implements MovieController {

	private final CreateMovieUseCase createMovieUseCase;
	private final SearchMovieUseCase searchMovieUseCase;
	private final UpdateMovieUseCase updateMovieUseCase;
	private final DeleteMovieUseCase deleteMovieUseCase;
	private final CreateMovieNoteUseCase createMovieNoteUseCase;
	private final SearchMovieNoteUseCase searchMovieNoteUseCase;
	private final UpdateMovieNoteUseCase updateMovieNoteUseCase;
	private final DeleteMovieNoteUseCase deleteMovieNoteUseCase;
	private final SearchMovieGenreUseCase searchMovieGenreUseCase;

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
	public List<MovieNoteResponseDto> listMovieNotes(UUID movieId, UserTokenDto user) throws MovieNotFoundException {
		return searchMovieNoteUseCase.list(movieId, user.getId())
				.stream()
				.map(MovieNoteResponseDto::new)
				.collect(Collectors.toList());
	}

	@Override
	public MovieNoteResponseDto createMovieNote(MovieNoteRequestDto request, UserTokenDto user)
			throws MovieNotFoundException, DuplicatedMovieNoteException {
		MovieNote response = createMovieNoteUseCase.create(request.getMovieId(), request.getNote(), user.getLogin());
		return new MovieNoteResponseDto(response);
	}

	@Override
	public MovieNoteResponseDto updateMovieNote(UUID movieId, MovieNoteRequestDto request, UserTokenDto user)
			throws MovieNoteNotFoundException {
		MovieNote response = updateMovieNoteUseCase.update(user.getId(), movieId, request.getNote());
		return new MovieNoteResponseDto(response);
	}

	@Override
	public void deleteMovieNote(UUID movieId, UserTokenDto user) {
		deleteMovieNoteUseCase.delete(user.getId(), movieId);
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
