package com.moviemux.movie.adapter.controller.impl;

import com.moviemux.adapter.core.controller.dto.UserTokenDto;
import com.moviemux.legacyboard.domain.repository.LegacyBoardInterlock;
import com.moviemux.movie.adapter.controller.MovieController;
import com.moviemux.movie.adapter.controller.dto.MovieGenreResponseDto;
import com.moviemux.movie.adapter.controller.dto.MovieNoteRequestDto;
import com.moviemux.movie.adapter.controller.dto.MovieNoteResponseDto;
import com.moviemux.movie.adapter.controller.dto.MovieRequestDto;
import com.moviemux.movie.adapter.controller.dto.MovieResponseDto;
import com.moviemux.movie.adapter.controller.mapper.MovieHttpMapper;
import com.moviemux.movie.domain.command.*;
import com.moviemux.movie.domain.exception.DuplicatedMovieException;
import com.moviemux.movie.domain.exception.DuplicatedMovieNoteException;
import com.moviemux.movie.domain.exception.MovieNotFoundException;
import com.moviemux.movie.domain.exception.MovieNoteNotFoundException;
import com.moviemux.movie.domain.model.Movie;
import com.moviemux.movie.domain.model.MovieGenre;
import com.moviemux.movie.domain.model.MovieNote;
import com.moviemux.movie.domain.usecase.CreateMovieNoteUseCase;
import com.moviemux.movie.domain.usecase.CreateMovieUseCase;
import com.moviemux.movie.domain.usecase.DeleteMovieNoteUseCase;
import com.moviemux.movie.domain.usecase.DeleteMovieUseCase;
import com.moviemux.movie.domain.usecase.GetMovieUseCase;
import com.moviemux.movie.domain.usecase.SearchMovieGenreUseCase;
import com.moviemux.movie.domain.usecase.SearchMovieGenresWithMoviesUseCase;
import com.moviemux.movie.domain.usecase.SearchMovieNoteUseCase;
import com.moviemux.movie.domain.usecase.SearchMovieUseCase;
import com.moviemux.movie.domain.usecase.UpdateMovieNoteUseCase;
import com.moviemux.movie.domain.usecase.UpdateMovieUseCase;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

@Controller
@RequiredArgsConstructor
public class MovieControllerImpl implements MovieController {

  private final LegacyBoardInterlock legacyBoard;

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
  @Transactional
  public Page<MovieResponseDto> listAllMovies(
      String title, String genre, String sortJoin, Pageable pageable, UserTokenDto user) {
    legacyBoard.requireLegacy();
    var response =
        searchMovieUseCase.execute(
            MovieHttpMapper.query(title, genre, sortJoin, pageable), user.getId());
    return MovieHttpMapper.page(response, pageable);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MovieResponseDto getById(UUID id, UserTokenDto user) throws MovieNoteNotFoundException {
    legacyBoard.requireLegacy();
    Movie response = getMovieUseCase.execute(id, user.getId());
    return MovieHttpMapper.movie(response);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MovieResponseDto save(MovieRequestDto movie, UserTokenDto user)
      throws DuplicatedMovieException {
    legacyBoard.requireLegacy();
    Movie response = createMovieUseCase.execute(MovieHttpMapper.create(movie, user.getId()));
    return MovieHttpMapper.movie(response);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MovieResponseDto update(MovieRequestDto movie, UUID movieId, UserTokenDto user)
      throws MovieNotFoundException, UserNotAuthorizedException {
    legacyBoard.requireLegacy();
    Movie response =
        updateMovieUseCase.execute(MovieHttpMapper.update(movie, movieId, user.getLogin()));
    return MovieHttpMapper.movie(response);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void delete(UUID id, UserTokenDto user) throws UserNotAuthorizedException {
    legacyBoard.requireLegacy();
    deleteMovieUseCase.execute(new DeleteMovieCommand(id, user.getId()));
  }

  @Override
  @Transactional
  public List<MovieNoteResponseDto> listMovieNotes(UUID movieId, UserTokenDto user)
      throws MovieNotFoundException {
    legacyBoard.requireLegacy();
    return searchMovieNoteUseCase.execute(movieId, user.getId()).stream()
        .map(MovieHttpMapper::note)
        .collect(Collectors.toList());
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MovieNoteResponseDto createMovieNote(MovieNoteRequestDto request, UserTokenDto user)
      throws MovieNotFoundException, DuplicatedMovieNoteException {
    legacyBoard.requireLegacy();
    MovieNote response =
        createMovieNoteUseCase.execute(
            new CreateMovieNoteCommand(request.getMovieId(), request.getNote(), user.getLogin()));
    return MovieHttpMapper.note(response);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public MovieNoteResponseDto updateMovieNote(
      UUID movieId, MovieNoteRequestDto request, UserTokenDto user)
      throws MovieNoteNotFoundException {
    legacyBoard.requireLegacy();
    MovieNote response =
        updateMovieNoteUseCase.execute(
            new UpdateMovieNoteCommand(user.getId(), movieId, request.getNote()));
    return MovieHttpMapper.note(response);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void deleteMovieNote(UUID movieId, UserTokenDto user) {
    legacyBoard.requireLegacy();
    deleteMovieNoteUseCase.execute(new DeleteMovieNoteCommand(user.getId(), movieId));
  }

  @Override
  public List<MovieGenreResponseDto> listGenres() {
    List<MovieGenre> genres = searchMovieGenreUseCase.execute();
    return genres.stream().map(MovieHttpMapper::genre).collect(Collectors.toList());
  }

  @Override
  @Transactional
  public List<MovieGenreResponseDto> listAllGenresWithMovies() {
    legacyBoard.requireLegacy();
    List<MovieGenre> genres = genresWithMovies.execute();
    return genres.stream().map(MovieHttpMapper::genre).collect(Collectors.toList());
  }
}
