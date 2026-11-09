package com.moviemux.movie.adapter.controller.impl;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.moviemux.movie.domain.exception.DuplicatedMovieException;
import com.moviemux.movie.domain.exception.DuplicatedMovieNoteException;
import com.moviemux.movie.domain.exception.MovieNotFoundException;
import com.moviemux.movie.domain.exception.MovieNoteNotFoundException;
import lombok.RequiredArgsConstructor;
import com.moviemux.movie.adapter.controller.mapper.MovieHttpMapper;
import com.moviemux.movie.domain.command.*;
import com.moviemux.movie.domain.usecase.GetMovieUseCase;
import com.moviemux.movie.domain.usecase.SearchMovieGenresWithMoviesUseCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;

import com.moviemux.adapter.core.controller.dto.UserTokenDto;
import com.moviemux.movie.adapter.controller.MovieController;
import com.moviemux.movie.adapter.controller.dto.MovieGenreResponseDto;
import com.moviemux.movie.adapter.controller.dto.MovieNoteRequestDto;
import com.moviemux.movie.adapter.controller.dto.MovieNoteResponseDto;
import com.moviemux.movie.adapter.controller.dto.MovieRequestDto;
import com.moviemux.movie.adapter.controller.dto.MovieResponseDto;
import com.moviemux.movie.domain.model.Movie;
import com.moviemux.movie.domain.model.MovieGenre;
import com.moviemux.movie.domain.model.MovieNote;
import com.moviemux.movie.domain.usecase.CreateMovieNoteUseCase;
import com.moviemux.movie.domain.usecase.CreateMovieUseCase;
import com.moviemux.movie.domain.usecase.DeleteMovieNoteUseCase;
import com.moviemux.movie.domain.usecase.DeleteMovieUseCase;
import com.moviemux.movie.domain.usecase.SearchMovieGenreUseCase;
import com.moviemux.movie.domain.usecase.SearchMovieNoteUseCase;
import com.moviemux.movie.domain.usecase.SearchMovieUseCase;
import com.moviemux.movie.domain.usecase.UpdateMovieNoteUseCase;
import com.moviemux.movie.domain.usecase.UpdateMovieUseCase;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;

@Controller
@RequiredArgsConstructor
public class MovieControllerImpl implements MovieController {

	private final CreateMovieUseCase createMovieUseCase;

	private final SearchMovieUseCase searchMovieUseCase;
    private final GetMovieUseCase getMovieUseCase;
    private final SearchMovieGenresWithMoviesUseCase genresWithMovies;

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
		var response = searchMovieUseCase.execute(MovieHttpMapper.query(title, genre, sortJoin, pageable), user.getId());
		return MovieHttpMapper.page(response,pageable);
	}

	@Override
	public MovieResponseDto getById(UUID id, UserTokenDto user) throws MovieNoteNotFoundException {
		Movie response = getMovieUseCase.execute(id, user.getId());
		return MovieHttpMapper.movie(response);
	}

	@Override
	public MovieResponseDto save(MovieRequestDto movie, UserTokenDto user) throws DuplicatedMovieException {
		Movie response = createMovieUseCase.execute(MovieHttpMapper.create(movie, user.getId()));
		return MovieHttpMapper.movie(response);
	}

	@Override
	public MovieResponseDto update(MovieRequestDto movie, UUID movieId, UserTokenDto user)
			throws MovieNotFoundException, UserNotAuthorizedException {
		Movie response = updateMovieUseCase.execute(MovieHttpMapper.update(movie, movieId, user.getLogin()));
		return MovieHttpMapper.movie(response);
	}

	@Override
	public void delete(UUID id, UserTokenDto user) throws UserNotAuthorizedException {
		deleteMovieUseCase.execute(new DeleteMovieCommand(id, user.getId()));
	}

	@Override
	public List<MovieNoteResponseDto> listMovieNotes(UUID movieId, UserTokenDto user) throws MovieNotFoundException {
		return searchMovieNoteUseCase.execute(movieId, user.getId())
				.stream()
				.map(MovieHttpMapper::note)
				.collect(Collectors.toList());
	}

	@Override
	public MovieNoteResponseDto createMovieNote(MovieNoteRequestDto request, UserTokenDto user)
			throws MovieNotFoundException, DuplicatedMovieNoteException {
		MovieNote response = createMovieNoteUseCase.execute(new CreateMovieNoteCommand(request.getMovieId(), request.getNote(), user.getLogin()));
		return MovieHttpMapper.note(response);
	}

	@Override
	public MovieNoteResponseDto updateMovieNote(UUID movieId, MovieNoteRequestDto request, UserTokenDto user)
			throws MovieNoteNotFoundException {
		MovieNote response = updateMovieNoteUseCase.execute(new UpdateMovieNoteCommand(user.getId(), movieId, request.getNote()));
		return MovieHttpMapper.note(response);
	}

	@Override
	public void deleteMovieNote(UUID movieId, UserTokenDto user) {
		deleteMovieNoteUseCase.execute(new DeleteMovieNoteCommand(user.getId(), movieId));
	}

	@Override
	public List<MovieGenreResponseDto> listGenres() {
		List<MovieGenre> genres = searchMovieGenreUseCase.execute();
		return genres.stream().map(MovieHttpMapper::genre).collect(Collectors.toList());
	}

	@Override
	public List<MovieGenreResponseDto> listAllGenresWithMovies() {
		List<MovieGenre> genres = genresWithMovies.execute();
		return genres.stream().map(MovieHttpMapper::genre).collect(Collectors.toList());
	}

}
