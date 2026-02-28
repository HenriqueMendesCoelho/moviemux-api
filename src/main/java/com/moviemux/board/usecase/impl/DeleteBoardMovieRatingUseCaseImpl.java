package com.moviemux.board.usecase.impl;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.moviemux.board.adapter.repository.BoardMemberRepository;
import com.moviemux.board.adapter.repository.BoardMovieRatingRepository;
import com.moviemux.board.adapter.repository.BoardMovieRepository;
import com.moviemux.board.adapter.repository.BoardRepository;
import com.moviemux.board.domain.Board;
import com.moviemux.board.domain.BoardMember;
import com.moviemux.board.domain.BoardMemberStatus;
import com.moviemux.board.domain.BoardMovie;
import com.moviemux.board.domain.BoardMovieRating;
import com.moviemux.board.usecase.DeleteBoardMovieRatingUseCase;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardMovieNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.domain.Movie;
import com.moviemux.movie.usecase.exception.MovieNotFoundException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class DeleteBoardMovieRatingUseCaseImpl implements DeleteBoardMovieRatingUseCase {

	private final BoardRepository boardRepository;
	private final BoardMemberRepository boardMemberRepository;
	private final BoardMovieRepository boardMovieRepository;
	private final BoardMovieRatingRepository boardMovieRatingRepository;
	private final MovieRepository movieRepository;

	@Override
	public void deleteRating(UUID boardPublicId, UUID movieId, UUID requestingUserId)
			throws BoardNotFoundException, BoardMemberNotFoundException, MovieNotFoundException, BoardMovieNotFoundException {

		Board board = boardRepository.findByPublicId(boardPublicId)
				.orElseThrow(BoardNotFoundException::new);

		BoardMember member = boardMemberRepository
				.findByBoardAndUser_IdAndStatus(board, requestingUserId, BoardMemberStatus.ACCEPTED)
				.orElseThrow(() -> new BoardMemberNotFoundException("You must be an accepted member to manage ratings"));

		Movie movie = movieRepository.findById(movieId)
				.orElseThrow(MovieNotFoundException::new);

		BoardMovie boardMovie = boardMovieRepository.findByBoardAndMovie(board, movie)
				.orElseThrow(BoardMovieNotFoundException::new);

		BoardMovieRating rating = boardMovieRatingRepository
				.findByBoardMovieAndUser(boardMovie, member.getUser()).orElse(null);

		if (rating != null) {
			boardMovieRatingRepository.delete(rating);
		}
	}
}
