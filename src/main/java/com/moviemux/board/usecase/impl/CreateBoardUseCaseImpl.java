package com.moviemux.board.usecase.impl;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.moviemux.board.adapter.repository.BoardMemberRepository;
import com.moviemux.board.adapter.repository.BoardRepository;
import com.moviemux.board.domain.Board;
import com.moviemux.board.domain.BoardMember;
import com.moviemux.board.domain.BoardMemberStatus;
import com.moviemux.board.usecase.CreateBoardUseCase;
import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.domain.User;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CreateBoardUseCaseImpl implements CreateBoardUseCase {

	private final BoardRepository boardRepository;
	private final BoardMemberRepository boardMemberRepository;
	private final UserRepository userRepository;

	@Override
	public Board create(Board board, UUID ownerId) {
		User owner = userRepository.findById(ownerId).orElse(null);
		board.setOwner(owner);

		Board savedBoard = boardRepository.saveAndFlush(board);

		BoardMember ownerMember = BoardMember.builder()
				.board(savedBoard)
				.user(owner)
				.status(BoardMemberStatus.ACCEPTED)
				.joinedAt(OffsetDateTime.now())
				.build();

		boardMemberRepository.save(ownerMember);

		return savedBoard;
	}
}
