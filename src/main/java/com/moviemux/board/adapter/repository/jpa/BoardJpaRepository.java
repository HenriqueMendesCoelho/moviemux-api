package com.moviemux.board.adapter.repository.jpa;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.moviemux.board.domain.Board;

@Primary
public interface BoardJpaRepository extends JpaRepository<Board, Long> {

	@Transactional(readOnly = true)
	Optional<Board> findByPublicId(UUID publicId);

}
