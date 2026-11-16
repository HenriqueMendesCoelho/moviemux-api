package com.moviemux.user.adapter.notification;

import com.moviemux.adapter.core.effects.AfterCommitEffects;
import com.moviemux.mail.domain.model.SendMailTemplate;
import com.moviemux.mail.domain.repository.MailDispatcher;
import com.moviemux.user.domain.repository.UserMailSender;
import org.springframework.stereotype.Component;

@Component
public class AfterCommitUserMailSender implements UserMailSender {
  private final MailDispatcher dispatcher;
  private final AfterCommitEffects effects;

  public AfterCommitUserMailSender(MailDispatcher dispatcher, AfterCommitEffects effects) {
    this.dispatcher = dispatcher;
    this.effects = effects;
  }

  @Override
  public void sendEmail(SendMailTemplate message) {
    effects.submit("account mail", () -> dispatcher.dispatch(message));
  }
}
