package com.moviemux.user.adapter.controller;

import com.moviemux.user.domain.command.BlockUserCommand;
import com.moviemux.user.domain.command.ChangePasswordCommand;
import com.moviemux.user.domain.command.DemoteUserCommand;
import com.moviemux.user.domain.command.PromoteUserCommand;
import com.moviemux.user.domain.command.UpdateAccountCommand;
import com.moviemux.user.domain.command.UpdateProfileCommand;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.user.domain.exception.UserNotFoundException;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.usecase.BlockUserUseCase;
import com.moviemux.user.domain.usecase.ChangePasswordUseCase;
import com.moviemux.user.domain.usecase.DemoteUserUseCase;
import com.moviemux.user.domain.usecase.PromoteUserUseCase;
import com.moviemux.user.domain.usecase.UpdateAccountUseCase;
import com.moviemux.user.domain.usecase.UpdateProfileUseCase;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Transport compatibility facade. Each intention has an independent core operation. */
@Component
@RequiredArgsConstructor
public class UserUpdateOperations {
  private final UpdateAccountUseCase updateAccount;
  private final UpdateProfileUseCase updateProfile;
  private final ChangePasswordUseCase changePassword;
  private final PromoteUserUseCase promoteUser;
  private final DemoteUserUseCase demoteUser;
  private final BlockUserUseCase blockUser;

  public User update(User user, UUID userId, String emailUserLoged)
      throws UserNotFoundException, UserNotAuthorizedException {
    return updateAccount.execute(new UpdateAccountCommand(user, userId, emailUserLoged));
  }

  public User updateUserProfile(UUID userId, String name, String email, boolean notify)
      throws UserNotFoundException {
    return updateProfile.execute(new UpdateProfileCommand(userId, name, email, notify));
  }

  public User updateUserPassoword(UUID userId, String password, String newPassword)
      throws UserNotFoundException, UserNotAuthorizedException {
    return changePassword.execute(new ChangePasswordCommand(userId, password, newPassword));
  }

  public User promoteUserToAdmin(UUID userId) throws UserNotFoundException {
    return promoteUser.execute(new PromoteUserCommand(userId));
  }

  public User demoteUserToAdmin(UUID userId) throws UserNotFoundException {
    return demoteUser.execute(new DemoteUserCommand(userId));
  }

  public User blockUser(UUID userId) throws UserNotFoundException {
    return blockUser.execute(new BlockUserCommand(userId));
  }
}
