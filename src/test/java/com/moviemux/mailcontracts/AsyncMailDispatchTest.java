package com.moviemux.mailcontracts;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.adapter.core.effects.AfterCommitEffects;
import com.moviemux.mail.adapter.notification.AsyncMailDispatcher;
import com.moviemux.mail.application.spring.configuration.MailExecutorConfig;
import com.moviemux.mail.domain.model.SendMailTemplate;
import com.moviemux.mail.domain.repository.EmailGateway;
import com.moviemux.user.adapter.notification.AfterCommitUserMailSender;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AsyncMailDispatchTest {
  @AfterEach
  void clearTransaction() {
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  private void begin() {
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  @Test
  void everyMailTypeQueuesOnlyAfterCommitAndEachTaskAttemptsDeliveryOnce() {
    begin();
    var queue = new ArrayDeque<Runnable>();
    var gateway = mock(EmailGateway.class);
    var sender = new AfterCommitUserMailSender(new AsyncMailDispatcher(gateway, queue::add),
        new AfterCommitEffects());
    var messages = List.of(
        SendMailTemplate.welcomeMail("synthetic@example.test", "Synthetic"),
        SendMailTemplate.forgotPasswordMail("synthetic@example.test", "Synthetic", "key"),
        SendMailTemplate.passwordUpdatedMail("synthetic@example.test", "Synthetic"),
        SendMailTemplate.accountBlockedMail("synthetic@example.test", "Synthetic"));
    messages.forEach(sender::sendEmail);
    assertThat(queue).isEmpty();
    verifyNoInteractions(gateway);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    assertThat(queue).hasSize(4);
    verifyNoInteractions(gateway);
    when(gateway.sendEmail(any())).thenThrow(new IllegalStateException("synthetic failure"));
    while (!queue.isEmpty()) assertThatCode(queue.remove()::run).doesNotThrowAnyException();
    messages.forEach(message -> verify(gateway).sendEmail(message));
    verifyNoMoreInteractions(gateway);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    assertThat(queue).isEmpty();
  }

  @Test
  void rollbackNeverQueuesOrSendsMail() {
    begin();
    var queue = new ArrayDeque<Runnable>();
    var gateway = mock(EmailGateway.class);
    new AfterCommitUserMailSender(new AsyncMailDispatcher(gateway, queue::add),
        new AfterCommitEffects()).sendEmail(
            SendMailTemplate.passwordUpdatedMail("synthetic@example.test", "Synthetic"));
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    assertThat(queue).isEmpty();
    verifyNoInteractions(gateway);
  }

  @Test
  void saturatedExecutorFailureNeverFallsBackToCallingProviderOrRetrying() {
    var gateway = mock(EmailGateway.class);
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    var dispatcher = new AsyncMailDispatcher(gateway, task -> {
      attempts.incrementAndGet();
      throw new RejectedExecutionException("synthetic queue full");
    });
    assertThatCode(() -> dispatcher.dispatch(
        SendMailTemplate.welcomeMail("synthetic@example.test", "Synthetic")))
        .doesNotThrowAnyException();
    assertThat(attempts).hasValue(1);
    verifyNoInteractions(gateway);
  }

  @Test
  void failureLogsContainNoRecipientResetLinkOrRemoteExceptionDetails() {
    var logger = (ch.qos.logback.classic.Logger)
        org.slf4j.LoggerFactory.getLogger(AsyncMailDispatcher.class);
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      var message = SendMailTemplate.forgotPasswordMail(
          "private-recipient@example.test", "Synthetic", "private-reset-key");
      EmailGateway failing = command -> {
        throw new IllegalStateException("private-remote-response " + command.metadata().get("reset_link"));
      };
      new AsyncMailDispatcher(failing, Runnable::run).dispatch(message);
      new AsyncMailDispatcher(failing, task -> {
        throw new RejectedExecutionException("private-recipient@example.test");
      }).dispatch(message);
      assertThat(appender.list).hasSize(2).allSatisfy(event -> {
        assertThat(event.getFormattedMessage()).contains("no automatic retry")
            .doesNotContain("private-recipient", "private-reset-key", "private-remote-response", "https://");
        assertThat(event.getThrowableProxy()).isNull();
      });
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @Test
  void realExecutorReturnsBeforeDeliveryFinishesOnAnotherThread() throws Exception {
    var executor = new MailExecutorConfig().mailExecutor();
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var deliveryThread = new AtomicReference<Thread>();
    EmailGateway gateway = message -> {
      deliveryThread.set(Thread.currentThread());
      started.countDown();
      try {
        if (!release.await(5, TimeUnit.SECONDS))
          throw new IllegalStateException("Synthetic test did not release provider");
        return EmailGateway.Delivery.SENT;
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(failure);
      } finally {
        finished.countDown();
      }
    };
    try {
      new AsyncMailDispatcher(gateway, executor)
          .dispatch(SendMailTemplate.welcomeMail("synthetic@example.test", "Synthetic"));
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(finished.getCount()).isEqualTo(1);
      assertThat(deliveryThread.get()).isNotSameAs(Thread.currentThread());
      assertThat(deliveryThread.get().getName()).startsWith("mail-delivery-");
      assertThat(executor.getQueue().remainingCapacity()).isEqualTo(100);
      assertThat(executor.getRejectedExecutionHandler())
          .isInstanceOf(java.util.concurrent.ThreadPoolExecutor.AbortPolicy.class);
    } finally {
      release.countDown();
      executor.shutdown();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(finished.getCount()).isZero();
  }
}
