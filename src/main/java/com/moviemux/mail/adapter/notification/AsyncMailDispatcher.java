package com.moviemux.mail.adapter.notification;

import com.moviemux.mail.domain.model.SendMailTemplate;
import com.moviemux.mail.domain.repository.EmailGateway;
import com.moviemux.mail.domain.repository.MailDispatcher;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class AsyncMailDispatcher implements MailDispatcher {
  private static final Logger LOG = LoggerFactory.getLogger(AsyncMailDispatcher.class);
  private final EmailGateway gateway;
  private final Executor executor;

  public AsyncMailDispatcher(EmailGateway gateway, @Qualifier("mailExecutor") Executor executor) {
    this.gateway = gateway;
    this.executor = executor;
  }

  @Override
  public void dispatch(SendMailTemplate message) {
    try {
      executor.execute(() -> deliver(message));
    } catch (RuntimeException failure) {
      logFailure("queueing", failure);
    }
  }

  private void deliver(SendMailTemplate message) {
    try {
      gateway.sendEmail(message);
    } catch (RuntimeException failure) {
      logFailure("delivery", failure);
    }
  }

  private static void logFailure(String operation, RuntimeException failure) {
    // Exception messages and stack traces can contain recipients, tokens or remote response bodies.
    LOG.warn(
        "Best effort mail {} failed; no automatic retry ({})",
        operation,
        failure.getClass().getSimpleName());
  }
}
