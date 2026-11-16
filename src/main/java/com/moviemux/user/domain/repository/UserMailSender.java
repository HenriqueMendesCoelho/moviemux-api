package com.moviemux.user.domain.repository;

import com.moviemux.mail.domain.model.SendMailTemplate;

/** Account mail is dispatched asynchronously, best effort, after the local change has committed. */
public interface UserMailSender {
  void sendEmail(SendMailTemplate message);
}
