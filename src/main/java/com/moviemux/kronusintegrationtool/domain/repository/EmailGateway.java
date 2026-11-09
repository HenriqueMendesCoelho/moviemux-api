package com.moviemux.kronusintegrationtool.domain.repository;

import com.moviemux.kronusintegrationtool.domain.SendMailTemplate;

public interface EmailGateway {
    enum Delivery { SENT, DISABLED }
    Delivery sendEmail(SendMailTemplate request);
}
