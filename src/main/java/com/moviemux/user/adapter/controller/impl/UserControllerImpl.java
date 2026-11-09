package com.moviemux.user.adapter.controller.impl;

import com.moviemux.adapter.core.controller.dto.UserTokenDto;
import com.moviemux.user.adapter.controller.UserController;
import com.moviemux.user.adapter.controller.dto.InviteResponseDto;
import com.moviemux.user.adapter.controller.dto.UserEmailRequestDto;
import com.moviemux.user.adapter.controller.dto.UserRedefinePasswordByKeyRequestDto;
import com.moviemux.user.adapter.controller.dto.UserRequestDto;
import com.moviemux.user.adapter.controller.dto.UserResponseAdmDto;
import com.moviemux.user.adapter.controller.dto.UserResponseDto;
import com.moviemux.user.domain.exception.DuplicatedUserException;
import com.moviemux.user.domain.exception.InviteNotValidException;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.user.domain.exception.UserNotFoundException;
import com.moviemux.user.domain.exception.UserRedefinePasswordKeyInvalid;
import com.moviemux.user.domain.exception.UserRedefinePasswordKeyNotFound;
import com.moviemux.user.domain.model.Invite;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.usecase.CreateInviteUseCase;
import com.moviemux.user.domain.usecase.CreateUserUseCase;
import com.moviemux.user.domain.usecase.DeleteInviteUseCase;
import com.moviemux.user.domain.usecase.DeleteUserUseCase;
import com.moviemux.user.domain.usecase.SearchInviteUseCase;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class UserControllerImpl implements UserController {
  private final com.moviemux.user.adapter.controller.mapper.UserRequestMapper requestMapper;
  private final CreateUserUseCase createUserUseCase;
  private final com.moviemux.user.adapter.controller.mapper.UserResponseMapper responseMapper;
  private final com.moviemux.user.adapter.controller.UserQueryOperations searchUserUseCase;
  private final com.moviemux.user.adapter.controller.UserUpdateOperations updateUserUseCase;
  private final DeleteUserUseCase deleteUserUseCase;
  private final SearchInviteUseCase searchInviteUseCase;
  private final CreateInviteUseCase createInviteUseCase;
  private final DeleteInviteUseCase deleteInviteUseCase;
  private final com.moviemux.user.adapter.controller.UserPasswordResetOperations
      redefinePasswordUseCase;
  private final com.moviemux.user.application.spring.AsyncPasswordReset asyncPasswordReset;

  @Override
  public UserResponseDto getUserByEmail(UserTokenDto request, String email)
      throws UserNotFoundException, UserNotAuthorizedException {
    User user = searchUserUseCase.getUserByEmail(email, request.getLogin());
    return responseMapper.toResponse(user);
  }

  @Override
  public UserResponseDto createUser(UserRequestDto request)
      throws DuplicatedUserException, InviteNotValidException {
    User user = createUserUseCase.execute(requestMapper.toRegistration(request));
    return responseMapper.toResponse(user);
  }

  @Override
  public UserResponseDto update(UserRequestDto request, UUID id, UserTokenDto userLoged)
      throws UserNotFoundException, UserNotAuthorizedException {
    User response =
        updateUserUseCase.update(requestMapper.toModel(request), id, userLoged.getLogin());
    return responseMapper.toResponse(response);
  }

  @Override
  public UserResponseDto updateUserProfile(UUID userId, String name, String email, boolean notify)
      throws UserNotFoundException {
    User response = updateUserUseCase.updateUserProfile(userId, name, email, notify);
    return responseMapper.toResponse(response);
  }

  @Override
  public UserResponseDto updateUserPassoword(UUID userId, String password, String newPassword)
      throws UserNotFoundException, UserNotAuthorizedException {
    User response = updateUserUseCase.updateUserPassoword(userId, password, newPassword);
    return responseMapper.toResponse(response);
  }

  @Override
  public UserResponseAdmDto getUserByEmailAdm(UserTokenDto request, String email)
      throws UserNotFoundException, UserNotAuthorizedException {
    User response = searchUserUseCase.getUserByEmail(email, request.getLogin());
    return responseMapper.toAdminResponse(response);
  }

  @Override
  public UserResponseAdmDto promoteUserToAdmin(UUID userId) throws UserNotFoundException {
    User response = updateUserUseCase.promoteUserToAdmin(userId);
    return responseMapper.toAdminResponse(response);
  }

  @Override
  public UserResponseAdmDto demoteUserToAdmin(UUID userId) throws UserNotFoundException {
    User response = updateUserUseCase.demoteUserToAdmin(userId);
    return responseMapper.toAdminResponse(response);
  }

  @Override
  public UserResponseAdmDto blockUser(UUID userId) throws UserNotFoundException {
    User response = updateUserUseCase.blockUser(userId);
    return responseMapper.toAdminResponse(response);
  }

  @Override
  public List<UserResponseAdmDto> getAllUsers() throws UserNotFoundException {
    return searchUserUseCase.getAllUsers().stream()
        .map(responseMapper::toAdminResponse)
        .collect(Collectors.toList());
  }

  @Override
  public void delete(UUID id) {
    deleteUserUseCase.execute(id);
  }

  @Override
  public List<InviteResponseDto> getAllInvites() {
    List<Invite> response = searchInviteUseCase.execute();
    return response.stream().map(InviteResponseDto::new).collect(Collectors.toList());
  }

  @Override
  public void createRedefinePasswordKey(UserEmailRequestDto request) {
    asyncPasswordReset.request(request.getEmail());
  }

  @Override
  public void redefinePasswordByKey(UserRedefinePasswordByKeyRequestDto request, String key)
      throws UserRedefinePasswordKeyNotFound,
          UserRedefinePasswordKeyInvalid,
          UserNotAuthorizedException {
    redefinePasswordUseCase.redefine(key, request.getEmail(), request.getPassword());
  }

  // Invites
  @Override
  public InviteResponseDto createUserInvite() {
    Invite response = createInviteUseCase.execute();
    return new InviteResponseDto(response);
  }

  @Override
  public void deleteInvite(String code) {
    deleteInviteUseCase.execute(code);
  }
}
