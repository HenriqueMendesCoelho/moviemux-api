package com.moviemux.movie.adapter.controller;

import com.moviemux.adapter.core.controller.dto.UserTokenDto;
import com.moviemux.movie.adapter.controller.dto.MovieGenreResponseDto;
import com.moviemux.movie.adapter.controller.dto.MovieNoteRequestDto;
import com.moviemux.movie.adapter.controller.dto.MovieNoteResponseDto;
import com.moviemux.movie.adapter.controller.dto.MovieRequestDto;
import com.moviemux.movie.adapter.controller.dto.MovieResponseDto;
import com.moviemux.movie.domain.exception.DuplicatedMovieException;
import com.moviemux.movie.domain.exception.DuplicatedMovieNoteException;
import com.moviemux.movie.domain.exception.MovieNotFoundException;
import com.moviemux.movie.domain.exception.MovieNoteNotFoundException;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface MovieController {

  Page<MovieResponseDto> listAllMovies(
      String title, String genre, String sortJoin, Pageable pageable, UserTokenDto user);

  MovieResponseDto getById(UUID id, UserTokenDto user) throws MovieNoteNotFoundException;

  MovieResponseDto save(MovieRequestDto movie, UserTokenDto user) throws DuplicatedMovieException;

  MovieResponseDto update(MovieRequestDto movie, UUID id, UserTokenDto user)
      throws MovieNotFoundException, UserNotAuthorizedException;

  void delete(UUID id, UserTokenDto user) throws UserNotAuthorizedException;

  List<MovieNoteResponseDto> listMovieNotes(UUID movieId, UserTokenDto user)
      throws MovieNotFoundException;

  MovieNoteResponseDto createMovieNote(MovieNoteRequestDto request, UserTokenDto user)
      throws MovieNotFoundException, DuplicatedMovieNoteException;

  MovieNoteResponseDto updateMovieNote(UUID movieId, MovieNoteRequestDto request, UserTokenDto user)
      throws MovieNoteNotFoundException;

  void deleteMovieNote(UUID movieId, UserTokenDto user);

  List<MovieGenreResponseDto> listGenres();

  List<MovieGenreResponseDto> listAllGenresWithMovies();
}
