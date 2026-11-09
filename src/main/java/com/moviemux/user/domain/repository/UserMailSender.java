package com.moviemux.user.domain.repository;

import com.moviemux.kronusintegrationtool.domain.SendMailTemplate;

/** Account mail is best effort after the local account change has committed. */
public interface UserMailSender {
  void sendEmail(SendMailTemplate message);
}
