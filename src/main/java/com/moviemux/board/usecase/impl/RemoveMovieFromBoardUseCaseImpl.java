package com.moviemux.board.usecase.impl;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.moviemux.board.adapter.repository.BoardMemberRepository;
import com.moviemux.board.adapter.repository.BoardMovieRepository;
import com.moviemux.board.adapter.repository.BoardPermissionRepository;
import com.moviemux.board.adapter.repository.BoardRepository;
import com.moviemux.board.domain.Board;
import com.moviemux.board.domain.BoardMemberStatus;
import com.moviemux.board.domain.BoardMovie;
import com.moviemux.board.domain.BoardPermissionType;
import com.moviemux.board.usecase.RemoveMovieFromBoardUseCase;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardMovieNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;
import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.domain.Movie;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class RemoveMovieFromBoardUseCaseImpl implements RemoveMovieFromBoardUseCase {

	private final BoardRepository boardRepository;
	private final BoardMemberRepository boardMemberRepository;
	private final BoardPermissionRepository boardPermissionRepository;
	private final BoardMovieRepository boardMovieRepository;
	private final MovieRepository movieRepository;

	@Override
	public void removeMovie(UUID boardPublicId, UUID movieId, UUID requestingUserId)
			throws BoardNotFoundException, BoardNotAuthorizedException, BoardMovieNotFoundException,
			BoardMemberNotFoundException {

		Board board = boardRepository.findByPublicId(boardPublicId)
				.orElseThrow(BoardNotFoundException::new);

		boolean isOwner = board.getOwner().getId().equals(requestingUserId);

		if (!isOwner) {
			boardMemberRepository.findByBoardAndUser_IdAndStatus(board, requestingUserId, BoardMemberStatus.ACCEPTED)
					.orElseThrow(() -> new BoardMemberNotFoundException("You are not an accepted member of this board"));

			boolean hasPermission = boardPermissionRepository.existsByBoardAndUser_IdAndPermission(
					board, requestingUserId, BoardPermissionType.REMOVE_MOVIE);

			if (!hasPermission) {
				throw new BoardNotAuthorizedException("You do not have permission to remove movies from this board");
			}
		}

		Movie movie = movieRepository.findById(movieId).orElse(null);

		if (movie == null) {
			throw new BoardMovieNotFoundException();
		}

		BoardMovie boardMovie = boardMovieRepository.findByBoardAndMovie(board, movie)
				.orElseThrow(BoardMovieNotFoundException::new);

		boardMovieRepository.delete(boardMovie);
	}
}
