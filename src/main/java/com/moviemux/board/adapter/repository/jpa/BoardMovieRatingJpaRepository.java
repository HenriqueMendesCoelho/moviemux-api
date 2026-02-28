package com.moviemux.board.adapter.repository.jpa;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import com.moviemux.board.domain.BoardMovie;
import com.moviemux.board.domain.BoardMovieRating;
import com.moviemux.user.domain.User;

@Primary
public interface BoardMovieRatingJpaRepository extends JpaRepository<BoardMovieRating, Long> {

	@Transactional(readOnly = true)
	Optional<BoardMovieRating> findByBoardMovieAndUser(BoardMovie boardMovie, User user);

	@Transactional(readOnly = true)
	Optional<BoardMovieRating> findByBoardMovieAndUser_Id(BoardMovie boardMovie, UUID userId);

	@Modifying
	@Transactional
	void deleteByBoardMovie_BoardAndUser(com.moviemux.board.domain.Board board, User user);

}
