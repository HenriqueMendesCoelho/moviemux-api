package com.moviemux.user.adapter.controller.impl;

import com.moviemux.core.adapter.controller.dto.UserTokenDto;
import com.moviemux.user.adapter.controller.UserController;
import com.moviemux.user.adapter.controller.dto.*;
import com.moviemux.user.domain.Invite;
import com.moviemux.user.domain.User;
import com.moviemux.user.usecase.*;
import com.moviemux.user.usecase.exception.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class UserControllerImpl implements UserController {

	private final CreateUserUseCase createUserUseCase;
	private final SearchUserUseCase searchUserUseCase;
	private final UpdateUserUseCase updateUserUseCase;
	private final DeleteUserUseCase deleteUserUseCase;
	private final SearchInviteUseCase searchInviteUseCase;
	private final CreateInviteUseCase createInviteUseCase;
	private final DeleteInviteUseCase deleteInviteUseCase;
	private final UserRedefinePasswordUseCase redefinePasswordUseCase;

	@Override
	public UserResponseDto getUserByEmail(UserTokenDto request, String email)
			throws UserNotFoundException, UserNotAuthorizedException {
		User user = searchUserUseCase.getUserByEmail(email, request.getLogin());
		return new UserResponseDto(user);
	}

	@Override
	public UserResponseDto createUser(UserRequestDto request) throws DuplicatedUserException, InviteNotValidException {
		User user = createUserUseCase.save(request.toDomain(), request.getInviteCode());
		return new UserResponseDto(user);
	}

	@Override
	public UserResponseDto update(UserRequestDto request, UUID id, UserTokenDto userLoged)
			throws UserNotFoundException, UserNotAuthorizedException {
		User response = updateUserUseCase.update(request.toDomain(), id, userLoged.getLogin());
		return new UserResponseDto(response);
	}

	@Override
	public UserResponseDto updateUserProfile(UUID userId, String name, String email, boolean notify)
			throws UserNotFoundException {
		User response = updateUserUseCase.updateUserProfile(userId, name, email, notify);
		return new UserResponseDto(response);
	}

	@Override
	public UserResponseDto updateUserPassoword(UUID userId, String password, String newPassword)
			throws UserNotFoundException, UserNotAuthorizedException {
		User response = updateUserUseCase.updateUserPassoword(userId, password, newPassword);
		return new UserResponseDto(response);
	}

	@Override
	public UserResponseAdmDto getUserByEmailAdm(UserTokenDto request, String email)
			throws UserNotFoundException, UserNotAuthorizedException {
		User response = searchUserUseCase.getUserByEmail(email, request.getLogin());
		return new UserResponseAdmDto(response);
	}

	@Override
	public UserResponseAdmDto promoteUserToAdmin(UUID userId) throws UserNotFoundException {
		User response = updateUserUseCase.promoteUserToAdmin(userId);
		return new UserResponseAdmDto(response);
	}

	@Override
	public UserResponseAdmDto demoteUserToAdmin(UUID userId) throws UserNotFoundException {
		User response = updateUserUseCase.demoteUserToAdmin(userId);
		return new UserResponseAdmDto(response);
	}

	@Override
	public UserResponseAdmDto blockUser(UUID userId) throws UserNotFoundException {
		User response = updateUserUseCase.blockUser(userId);
		return new UserResponseAdmDto(response);
	}

	@Override
	public List<UserResponseAdmDto> getAllUsers() throws UserNotFoundException {
		return searchUserUseCase.getAllUsers().stream().map(UserResponseAdmDto::new).collect(Collectors.toList());
	}

	@Override
	public void delete(UUID id) {
		deleteUserUseCase.deleteUser(id);
	}

	@Override
	public List<InviteResponseDto> getAllInvites() {
		List<Invite> response = searchInviteUseCase.list();
		return response.stream().map(InviteResponseDto::new).collect(Collectors.toList());
	}

	@Override
	public void createRedefinePasswordKey(UserEmailRequestDto request) {
		redefinePasswordUseCase.createRedefinePassword(request.getEmail());
	}

	@Override
	public void redefinePasswordByKey(UserRedefinePasswordByKeyRequestDto request, String key)
			throws UserRedefinePasswordKeyNotFound, UserRedefinePasswordKeyInvalid, UserNotAuthorizedException {
		redefinePasswordUseCase.redefine(key, request.getEmail(), request.getPassword());

	}

	// Invites
	@Override
	public InviteResponseDto createUserInvite() {
		Invite response = createInviteUseCase.create();
		return new InviteResponseDto(response);
	}

	@Override
	public void deleteInvite(String code) {
		deleteInviteUseCase.delete(code);
	}

}
