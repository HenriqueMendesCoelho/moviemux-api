package com.moviemux.mail.domain.repository;

import com.moviemux.mail.domain.model.SendMailTemplate;

/** Provider boundary; each call attempts one delivery without retry. */
public interface EmailGateway {
  enum Delivery {
    SENT,
    DISABLED
  }

  Delivery sendEmail(SendMailTemplate request);
}
