package com.moviemux.user.adapter.notification;

import com.moviemux.application.spring.effects.AfterCommitEffects;
import com.moviemux.kronusintegrationtool.domain.model.SendMailTemplate;
import com.moviemux.kronusintegrationtool.domain.repository.EmailGateway;
import com.moviemux.user.domain.repository.UserMailSender;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;

@Component
public class AfterCommitUserMailSender implements UserMailSender {
  private final EmailGateway gateway;
  private final AfterCommitEffects effects;

  public AfterCommitUserMailSender(EmailGateway gateway, AfterCommitEffects effects) {
    this.gateway = gateway;
    this.effects = effects;
  }

  @Override
  public void sendEmail(SendMailTemplate message) {
    SendMailTemplate snapshot =
        new SendMailTemplate(
            message.getFrom(),
            message.getName(),
            message.getTo(),
            message.getTemplateId(),
            message.getSubject(),
            message.isIgnoreNotifyPreferences(),
            message.getParams() == null ? null : new LinkedHashMap<>(message.getParams()));
    effects.submit("account mail", () -> gateway.sendEmail(snapshot));
  }
}
