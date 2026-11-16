package com.moviemux.application.spring.listener;

import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthenticationFailureListener
    implements ApplicationListener<AuthenticationFailureBadCredentialsEvent> {

  private final UserRepository repository;

  @Override
  public void onApplicationEvent(AuthenticationFailureBadCredentialsEvent event) {
    String email = (String) event.getAuthentication().getPrincipal();

    if (email.isBlank()) {
      return;
    }

    failedLoginAttempts(email);
  }

  private void failedLoginAttempts(String email) {
    User user = repository.findByEmail(email);

    if (user == null) {
      return;
    }

    int ConsecutiveFailedLoginAttempts = user.getStatistics().getConsecutiveFailedLoginAttempts();
    user.getStatistics().setConsecutiveFailedLoginAttempts(ConsecutiveFailedLoginAttempts + 1);

    repository.save(user);
  }
}
