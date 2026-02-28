package com.moviemux.board.adapter.repository.jpa;

import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.moviemux.board.domain.Board;
import com.moviemux.board.domain.BoardMovie;
import com.moviemux.movie.domain.Movie;

@Primary
public interface BoardMovieJpaRepository extends JpaRepository<BoardMovie, Long> {

	@Transactional(readOnly = true)
	Optional<BoardMovie> findByBoardAndMovie(Board board, Movie movie);

	@Transactional(readOnly = true)
	boolean existsByBoardAndMovie(Board board, Movie movie);

}
