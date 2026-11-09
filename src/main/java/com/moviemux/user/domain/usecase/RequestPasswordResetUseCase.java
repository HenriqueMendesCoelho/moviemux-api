package com.moviemux.user.domain.usecase;

import com.moviemux.kronusintegrationtool.domain.SendMailTemplate;
import com.moviemux.kronusintegrationtool.domain.repository.EmailGateway;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.CodeGenerator;
import com.moviemux.user.domain.repository.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RequestPasswordResetUseCase {
  private final UserRepository repository;
  private final Clock clock;
  private final CodeGenerator codes;
  private final EmailGateway kronusIntegrationToolRepository;

  public void execute(String email) {
    User user = repository.findByEmail(email);
    if (user == null) {
      return;
    }

    String key = codes.passwordResetKey();

    kronusIntegrationToolRepository.sendEmail(
        SendMailTemplate.forgotPasswordMail(user.getEmail(), user.getName(), key));

    user.setRedefinePasswordKey(key);
    user.setRedefinePasswordKeyCreatedAt(OffsetDateTime.now(clock));

    repository.save(user);
  }
}
