package com.moviemux.board.adapter.repository.jpa;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import com.moviemux.board.domain.Board;
import com.moviemux.board.domain.BoardPermission;
import com.moviemux.board.domain.BoardPermissionType;
import com.moviemux.user.domain.User;

@Primary
public interface BoardPermissionJpaRepository extends JpaRepository<BoardPermission, Long> {

	@Transactional(readOnly = true)
	Optional<BoardPermission> findByBoardAndUser_IdAndPermission(Board board, UUID userId,
			BoardPermissionType permission);

	@Transactional(readOnly = true)
	List<BoardPermission> findByBoardAndUser_Id(Board board, UUID userId);

	@Transactional(readOnly = true)
	boolean existsByBoardAndUser_IdAndPermission(Board board, UUID userId, BoardPermissionType permission);

	@Modifying
	@Transactional
	void deleteByBoardAndUser(Board board, User user);

	@Modifying
	@Transactional
	void deleteByBoardAndUser_IdAndPermission(Board board, UUID userId, BoardPermissionType permission);

}
