package com.moviemux.user.domain.usecase;

import com.moviemux.kronusintegrationtool.domain.SendMailTemplate;
import com.moviemux.user.domain.repository.UserMailSender;
import com.moviemux.user.domain.model.User;
import com.moviemux.user.domain.repository.CodeGenerator;
import com.moviemux.user.domain.repository.UserRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Transactional(rollbackFor = Exception.class)
public class RequestPasswordResetUseCase {
  private final UserRepository repository;
  private final Clock clock;
  private final CodeGenerator codes;
  private final UserMailSender kronusIntegrationToolRepository;

  public void execute(String email) {
    User user = repository.findByEmail(email);
    if (user == null) {
      return;
    }

    String key = codes.passwordResetKey();

    user.setRedefinePasswordKey(key);
    user.setRedefinePasswordKeyCreatedAt(OffsetDateTime.now(clock));

    repository.save(user);

    kronusIntegrationToolRepository.sendEmail(
        SendMailTemplate.forgotPasswordMail(user.getEmail(), user.getName(), key));
  }
}
