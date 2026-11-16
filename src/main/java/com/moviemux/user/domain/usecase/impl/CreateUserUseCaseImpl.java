package com.moviemux.user.domain.usecase.impl;

import com.moviemux.mail.domain.model.SendMailTemplate;
import com.moviemux.user.domain.repository.UserMailSender;
import com.moviemux.user.domain.model.Role;
import com.moviemux.user.domain.exception.DuplicatedUserException;
import com.moviemux.user.domain.exception.InviteNotValidException;
import com.moviemux.user.domain.model.Invite;
import com.moviemux.user.domain.model.Preferences;
import com.moviemux.user.domain.model.Statistics;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.InviteRepository;
import com.moviemux.user.domain.repository.PasswordHasher;
import com.moviemux.user.domain.repository.UserRepository;
import com.moviemux.user.domain.usecase.CreateUserUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

@Component
@Log4j2
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class CreateUserUseCaseImpl implements CreateUserUseCase {
  private final UserRepository repository;
  private final InviteRepository inviteRepository;
  private final PasswordHasher passwordEncoder;
  private final UserMailSender mailSender;

  @Override
  public User execute(com.moviemux.user.domain.command.RegisterUserCommand command)
      throws DuplicatedUserException, InviteNotValidException {
    User user = command.user();
    String inviteCode = command.inviteCode();

    if (repository.count() > 0) {
      Invite invite = inviteRepository.findByCode(inviteCode);

      if (invite == null) {
        throw new InviteNotValidException();
      }

      inviteRepository.delete(invite);
    } else {
      user.addRole(Role.ADM);
    }

    if (repository.findByEmail(user.getEmail()) != null) {
      throw new DuplicatedUserException();
    }
    user.setPassword(passwordEncoder.encode(user.getPassword()));
    user.setStatistics(Statistics.builder().user(user).ratingsGiven(0).registeredMovies(0).build());
    user.setPreferences(Preferences.builder().user(user).notify(true).build());

    User userCreated = repository.save(user);

    sendWelcomeMail(userCreated);

    return userCreated;
  }

  private void sendWelcomeMail(User user) {
    try {
      mailSender.sendEmail(
          SendMailTemplate.welcomeMail(user.getEmail(), user.getName()));
    } catch (Exception e) {
      log.warn("Best effort welcome mail could not be scheduled ({})", e.getClass().getSimpleName());
    }
  }
}
