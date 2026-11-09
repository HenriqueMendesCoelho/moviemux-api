package com.moviemux.application.spring.listener;

import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.UserRepository;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthenticationSuccessEventListener
    implements ApplicationListener<AuthenticationSuccessEvent> {

  private final UserRepository repository;

  @Override
  public void onApplicationEvent(AuthenticationSuccessEvent event) {
    String email = (String) event.getAuthentication().getName();

    if (StringUtils.isEmpty(email)) {
      return;
    }

    updateUserStatistics(email);
  }

  private void updateUserStatistics(String email) {
    User user = repository.findByEmail(email);

    if (user == null) {
      return;
    }

    user.getStatistics().setConsecutiveFailedLoginAttempts(0);
    user.getStatistics().setLastLoginAt(OffsetDateTime.now());

    repository.save(user);
  }
}
