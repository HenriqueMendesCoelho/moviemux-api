package com.moviemux.mail.domain.repository;

import com.moviemux.mail.domain.model.SendMailTemplate;

/** Best effort asynchronous dispatch, with no durable queue or automatic retry. */
public interface MailDispatcher {
  void dispatch(SendMailTemplate message);
}
