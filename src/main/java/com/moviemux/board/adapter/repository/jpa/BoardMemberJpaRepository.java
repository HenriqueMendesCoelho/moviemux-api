package com.moviemux.board.adapter.repository.jpa;

import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import com.moviemux.board.domain.Board;
import com.moviemux.board.domain.BoardMember;
import com.moviemux.board.domain.BoardMemberStatus;
import com.moviemux.user.domain.User;

@Primary
public interface BoardMemberJpaRepository extends JpaRepository<BoardMember, Long> {

	@Transactional(readOnly = true)
	Optional<BoardMember> findByBoardAndUser(Board board, User user);

	@Transactional(readOnly = true)
	Optional<BoardMember> findByBoardAndUser_Id(Board board, UUID userId);

	@Transactional(readOnly = true)
	Optional<BoardMember> findByBoardAndUser_IdAndStatus(Board board, UUID userId, BoardMemberStatus status);

	@Transactional(readOnly = true)
	boolean existsByBoardAndUser_IdAndStatusIn(Board board, UUID userId, Iterable<BoardMemberStatus> statuses);

	@Modifying
	@Transactional
	void deleteByBoardAndUser(Board board, User user);

}
