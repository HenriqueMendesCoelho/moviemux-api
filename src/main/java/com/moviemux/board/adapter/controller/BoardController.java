package com.moviemux.board.adapter.controller;

import java.util.UUID;

import com.moviemux.adapter.core.controller.dto.UserTokenDto;
import com.moviemux.board.adapter.controller.dto.BoardMovieRatingRequestDto;
import com.moviemux.board.adapter.controller.dto.BoardMovieRatingResponseDto;
import com.moviemux.board.adapter.controller.dto.BoardMovieResponseDto;
import com.moviemux.board.adapter.controller.dto.BoardMemberResponseDto;
import com.moviemux.board.adapter.controller.dto.BoardPermissionRequestDto;
import com.moviemux.board.adapter.controller.dto.BoardPermissionResponseDto;
import com.moviemux.board.adapter.controller.dto.BoardRequestDto;
import com.moviemux.board.adapter.controller.dto.BoardResponseDto;
import com.moviemux.board.usecase.exception.BoardMemberNotFoundException;
import com.moviemux.board.usecase.exception.BoardMovieNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotFoundException;
import com.moviemux.board.usecase.exception.BoardNotAuthorizedException;
import com.moviemux.board.usecase.exception.DuplicateBoardMemberException;
import com.moviemux.board.usecase.exception.DuplicateBoardMovieException;
import com.moviemux.movie.usecase.exception.MovieNotFoundException;
import com.moviemux.user.usecase.exception.UserNotFoundException;

public interface BoardController {

	BoardResponseDto createBoard(BoardRequestDto request, UserTokenDto user);

	BoardResponseDto getBoard(UUID boardPublicId, UserTokenDto user) throws BoardNotFoundException;

	void deleteBoard(UUID boardPublicId, UserTokenDto user) throws BoardNotFoundException, BoardNotAuthorizedException;

	BoardMemberResponseDto inviteUser(UUID boardPublicId, UUID targetUserPublicId, UserTokenDto user)
			throws BoardNotFoundException, BoardNotAuthorizedException, DuplicateBoardMemberException,
			UserNotFoundException, BoardMemberNotFoundException;

	BoardMemberResponseDto acceptInvite(UUID boardPublicId, UUID userPublicId, UserTokenDto user)
			throws BoardNotFoundException, BoardMemberNotFoundException, BoardNotAuthorizedException;

	BoardMemberResponseDto rejectInvite(UUID boardPublicId, UUID userPublicId, UserTokenDto user)
			throws BoardNotFoundException, BoardMemberNotFoundException, BoardNotAuthorizedException;

	void removeMember(UUID boardPublicId, UUID targetUserPublicId, UserTokenDto user)
			throws BoardNotFoundException, BoardNotAuthorizedException, BoardMemberNotFoundException;

	BoardPermissionResponseDto addPermission(UUID boardPublicId, UUID targetUserPublicId,
			BoardPermissionRequestDto request, UserTokenDto user)
			throws BoardNotFoundException, BoardNotAuthorizedException, BoardMemberNotFoundException;

	void removePermission(UUID boardPublicId, UUID targetUserPublicId, String permission, UserTokenDto user)
			throws BoardNotFoundException, BoardNotAuthorizedException;

	BoardResponseDto transferOwnership(UUID boardPublicId, UUID newOwnerPublicId, UserTokenDto user)
			throws BoardNotFoundException, BoardNotAuthorizedException, BoardMemberNotFoundException;

	BoardMovieResponseDto addMovie(UUID boardPublicId, UUID movieId, UserTokenDto user)
			throws BoardNotFoundException, BoardNotAuthorizedException, MovieNotFoundException,
			DuplicateBoardMovieException, BoardMemberNotFoundException;

	void removeMovie(UUID boardPublicId, UUID movieId, UserTokenDto user)
			throws BoardNotFoundException, BoardNotAuthorizedException, BoardMovieNotFoundException,
			BoardMemberNotFoundException;

	BoardMovieRatingResponseDto rateMovie(UUID boardPublicId, UUID movieId, BoardMovieRatingRequestDto request,
			UserTokenDto user)
			throws BoardNotFoundException, BoardMemberNotFoundException, MovieNotFoundException, BoardMovieNotFoundException;

	void deleteRating(UUID boardPublicId, UUID movieId, UserTokenDto user)
			throws BoardNotFoundException, BoardMemberNotFoundException, MovieNotFoundException, BoardMovieNotFoundException;

}
