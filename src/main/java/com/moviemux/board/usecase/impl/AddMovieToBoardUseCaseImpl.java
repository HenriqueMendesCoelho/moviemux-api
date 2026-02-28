package com.moviemux.board.usecase.impl;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.moviemux.board.adapter.repository.BoardMemberRepository;
import com.moviemux.board.adapter.repository.BoardMovieRepository;
import com.moviemux.board.adapter.repository.BoardPermissionRepository;
import com.moviemux.board.adapter.repository.BoardRepository;
import com.moviemux.board.domain.Board;
import com.moviemux.board.domain.BoardMember;
import com.moviemux.board.domain.BoardMemberStatus;
import com.moviemux.board.domain.BoardMovie;
import com.moviemux.board.domain.BoardPermissionType;
import com.moviemux.board.usecase.AddMovieToBoardUseCase;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;
import com.moviemux.board.usecase.exception.DuplicateBoardMovieException;
import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.domain.Movie;
import com.moviemux.movie.usecase.exception.MovieNotFoundException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AddMovieToBoardUseCaseImpl implements AddMovieToBoardUseCase {

	private final BoardRepository boardRepository;
	private final BoardMemberRepository boardMemberRepository;
	private final BoardPermissionRepository boardPermissionRepository;
	private final BoardMovieRepository boardMovieRepository;
	private final MovieRepository movieRepository;

	@Override
	public BoardMovie addMovie(UUID boardPublicId, UUID movieId, UUID requestingUserId)
			throws BoardNotFoundException, BoardNotAuthorizedException, MovieNotFoundException,
			DuplicateBoardMovieException, BoardMemberNotFoundException {

		Board board = boardRepository.findByPublicId(boardPublicId)
				.orElseThrow(BoardNotFoundException::new);

		boolean isOwner = board.getOwner().getId().equals(requestingUserId);

		if (!isOwner) {
			boardMemberRepository.findByBoardAndUser_IdAndStatus(board, requestingUserId, BoardMemberStatus.ACCEPTED)
					.orElseThrow(() -> new BoardMemberNotFoundException("You are not an accepted member of this board"));

			boolean hasPermission = boardPermissionRepository.existsByBoardAndUser_IdAndPermission(
					board, requestingUserId, BoardPermissionType.ADD_MOVIE);

			if (!hasPermission) {
				throw new BoardNotAuthorizedException("You do not have permission to add movies to this board");
			}
		}

		Movie movie = movieRepository.findById(movieId)
				.orElseThrow(MovieNotFoundException::new);

		if (boardMovieRepository.existsByBoardAndMovie(board, movie)) {
			throw new DuplicateBoardMovieException();
		}

		BoardMember requestingMember = boardMemberRepository.findByBoardAndUser_Id(board, requestingUserId)
				.orElse(null);

		BoardMovie boardMovie = BoardMovie.builder()
				.board(board)
				.movie(movie)
				.addedBy(requestingMember != null ? requestingMember.getUser() : board.getOwner())
				.build();

		return boardMovieRepository.save(boardMovie);
	}
}
