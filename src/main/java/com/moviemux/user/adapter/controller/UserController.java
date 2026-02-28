package com.moviemux.user.adapter.controller;

import com.moviemux.core.adapter.controller.dto.UserTokenDto;
import com.moviemux.user.adapter.controller.dto.*;
import com.moviemux.user.usecase.exception.*;

import java.util.List;
import java.util.UUID;

public interface UserController {

	List<UserResponseAdmDto> getAllUsers() throws UserNotFoundException;

	UserResponseDto getUserByEmail(UserTokenDto user, String email)
			throws UserNotFoundException, UserNotAuthorizedException;

	UserResponseDto createUser(UserRequestDto user) throws DuplicatedUserException, InviteNotValidException;

	UserResponseDto update(UserRequestDto user, UUID id, UserTokenDto userLoged)
			throws UserNotFoundException, UserNotAuthorizedException;

	UserResponseDto updateUserProfile(UUID userId, String name, String email, boolean notify)
			throws UserNotFoundException;

	UserResponseDto updateUserPassoword(UUID userId, String password, String newPassword)
			throws UserNotFoundException, UserNotAuthorizedException;

	UserResponseAdmDto getUserByEmailAdm(UserTokenDto request, String email)
			throws UserNotFoundException, UserNotAuthorizedException;

	UserResponseAdmDto promoteUserToAdmin(UUID userId) throws UserNotFoundException;

	UserResponseAdmDto demoteUserToAdmin(UUID userId) throws UserNotFoundException;

	UserResponseAdmDto blockUser(UUID userId) throws UserNotFoundException;

	void createRedefinePasswordKey(UserEmailRequestDto request);

	void redefinePasswordByKey(UserRedefinePasswordByKeyRequestDto request, String key)
			throws UserRedefinePasswordKeyNotFound, UserRedefinePasswordKeyInvalid, UserNotAuthorizedException;

	void delete(UUID id);

	// Invites
	List<InviteResponseDto> getAllInvites();

	InviteResponseDto createUserInvite();

	void deleteInvite(String code);

}
