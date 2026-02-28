package com.moviemux.board.adapter.controller.impl;

import java.util.UUID;

import org.springframework.stereotype.Controller;

import com.moviemux.adapter.core.controller.dto.UserTokenDto;
import com.moviemux.board.adapter.controller.BoardController;
import com.moviemux.board.adapter.controller.dto.*;
import com.moviemux.board.domain.*;
import com.moviemux.board.usecase.*;
import com.moviemux.board.usecase.exception.*;
import com.moviemux.movie.usecase.exception.MovieNotFoundException;
import com.moviemux.user.usecase.exception.UserNotFoundException;

import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class BoardControllerImpl implements BoardController {

	private final CreateBoardUseCase createBoardUseCase;
	private final GetBoardUseCase getBoardUseCase;
	private final DeleteBoardUseCase deleteBoardUseCase;
	private final InviteUserToBoardUseCase inviteUserToBoardUseCase;
	private final AcceptBoardInviteUseCase acceptBoardInviteUseCase;
	private final RejectBoardInviteUseCase rejectBoardInviteUseCase;
	private final RemoveBoardMemberUseCase removeBoardMemberUseCase;
	private final AddBoardPermissionUseCase addBoardPermissionUseCase;
	private final RemoveBoardPermissionUseCase removeBoardPermissionUseCase;
	private final TransferBoardOwnershipUseCase transferBoardOwnershipUseCase;
	private final AddMovieToBoardUseCase addMovieToBoardUseCase;
	private final RemoveMovieFromBoardUseCase removeMovieFromBoardUseCase;
	private final RateBoardMovieUseCase rateBoardMovieUseCase;
	private final DeleteBoardMovieRatingUseCase deleteBoardMovieRatingUseCase;

	@Override
	public BoardResponseDto createBoard(BoardRequestDto request, UserTokenDto user) {
		Board board = createBoardUseCase.create(request.toDomain(), user.getId());
		return new BoardResponseDto(board);
	}

	@Override
	public BoardResponseDto getBoard(UUID boardPublicId, UserTokenDto user) throws BoardNotFoundException {
		Board board = getBoardUseCase.get(boardPublicId);
		return new BoardResponseDto(board);
	}

	@Override
	public void deleteBoard(UUID boardPublicId, UserTokenDto user)
			throws BoardNotFoundException, BoardNotAuthorizedException {
		deleteBoardUseCase.delete(boardPublicId, user.getId());
	}

	@Override
	public BoardMemberResponseDto inviteUser(UUID boardPublicId, UUID targetUserPublicId, UserTokenDto user)
			throws BoardNotFoundException, BoardNotAuthorizedException, DuplicateBoardMemberException,
			UserNotFoundException, BoardMemberNotFoundException {
		BoardMember member = inviteUserToBoardUseCase.invite(boardPublicId, targetUserPublicId, user.getId());
		return new BoardMemberResponseDto(member);
	}

	@Override
	public BoardMemberResponseDto acceptInvite(UUID boardPublicId, UUID userPublicId, UserTokenDto user)
			throws BoardNotFoundException, BoardMemberNotFoundException, BoardNotAuthorizedException {
		BoardMember member = acceptBoardInviteUseCase.accept(boardPublicId, userPublicId, user.getId());
		return new BoardMemberResponseDto(member);
	}

	@Override
	public BoardMemberResponseDto rejectInvite(UUID boardPublicId, UUID userPublicId, UserTokenDto user)
			throws BoardNotFoundException, BoardMemberNotFoundException, BoardNotAuthorizedException {
		BoardMember member = rejectBoardInviteUseCase.reject(boardPublicId, userPublicId, user.getId());
		return new BoardMemberResponseDto(member);
	}

	@Override
	public void removeMember(UUID boardPublicId, UUID targetUserPublicId, UserTokenDto user)
			throws BoardNotFoundException, BoardNotAuthorizedException, BoardMemberNotFoundException {
		removeBoardMemberUseCase.remove(boardPublicId, targetUserPublicId, user.getId());
	}

	@Override
	public BoardPermissionResponseDto addPermission(UUID boardPublicId, UUID targetUserPublicId,
			BoardPermissionRequestDto request, UserTokenDto user)
			throws BoardNotFoundException, BoardNotAuthorizedException, BoardMemberNotFoundException {
		BoardPermission permission = addBoardPermissionUseCase.addPermission(
				boardPublicId, targetUserPublicId, request.getPermission(), user.getId());
		return new BoardPermissionResponseDto(permission);
	}

	@Override
	public void removePermission(UUID boardPublicId, UUID targetUserPublicId, String permission, UserTokenDto user)
			throws BoardNotFoundException, BoardNotAuthorizedException {
		BoardPermissionType permissionType = BoardPermissionType.valueOf(permission.toUpperCase());
		removeBoardPermissionUseCase.removePermission(boardPublicId, targetUserPublicId, permissionType, user.getId());
	}

	@Override
	public BoardResponseDto transferOwnership(UUID boardPublicId, UUID newOwnerPublicId, UserTokenDto user)
			throws BoardNotFoundException, BoardNotAuthorizedException, BoardMemberNotFoundException {
		Board board = transferBoardOwnershipUseCase.transfer(boardPublicId, newOwnerPublicId, user.getId());
		return new BoardResponseDto(board);
	}

	@Override
	public BoardMovieResponseDto addMovie(UUID boardPublicId, UUID movieId, UserTokenDto user)
			throws BoardNotFoundException, BoardNotAuthorizedException, MovieNotFoundException,
			DuplicateBoardMovieException, BoardMemberNotFoundException {
		BoardMovie boardMovie = addMovieToBoardUseCase.addMovie(boardPublicId, movieId, user.getId());
		return new BoardMovieResponseDto(boardMovie);
	}

	@Override
	public void removeMovie(UUID boardPublicId, UUID movieId, UserTokenDto user)
			throws BoardNotFoundException, BoardNotAuthorizedException, BoardMovieNotFoundException,
			BoardMemberNotFoundException {
		removeMovieFromBoardUseCase.removeMovie(boardPublicId, movieId, user.getId());
	}

	@Override
	public BoardMovieRatingResponseDto rateMovie(UUID boardPublicId, UUID movieId,
			BoardMovieRatingRequestDto request, UserTokenDto user)
			throws BoardNotFoundException, BoardMemberNotFoundException, MovieNotFoundException, BoardMovieNotFoundException {
		BoardMovieRating rating = rateBoardMovieUseCase.rate(boardPublicId, movieId, user.getId(), request.getRating());
		return new BoardMovieRatingResponseDto(rating);
	}

	@Override
	public void deleteRating(UUID boardPublicId, UUID movieId, UserTokenDto user)
			throws BoardNotFoundException, BoardMemberNotFoundException, MovieNotFoundException, BoardMovieNotFoundException {
		deleteBoardMovieRatingUseCase.deleteRating(boardPublicId, movieId, user.getId());
	}
}
