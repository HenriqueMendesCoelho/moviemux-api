package com.moviemux.transactioneffects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.moviemux.adapter.core.effects.AfterCommitEffects;
import com.moviemux.mail.domain.model.SendMailTemplate;
import com.moviemux.mail.domain.repository.MailDispatcher;
import com.moviemux.user.adapter.notification.AfterCommitUserMailSender;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AfterCommitEffectsTest {
  private final AfterCommitEffects effects = new AfterCommitEffects();

  @AfterEach
  void resetThreadState() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  private void begin() {
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  @Test
  void activeTransactionDefersUntilCommitAndRollbackDoesNotDeliver() {
    begin();
    AtomicInteger calls = new AtomicInteger();
    assertThat(effects.submit("synthetic", calls::incrementAndGet)).isTrue();
    assertThat(calls).hasValue(0);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(
            callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    assertThat(calls).hasValue(0);
  }

  @Test
  void commitDeliversOnceAndFailureDoesNotPropagateOrRetry() {
    begin();
    AtomicInteger calls = new AtomicInteger();
    effects.submit(
        "synthetic",
        () -> {
          calls.incrementAndGet();
          throw new IllegalStateException("synthetic failure");
        });
    assertThatCode(
            () ->
                TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit))
        .doesNotThrowAnyException();
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    assertThat(calls).hasValue(1);
  }

  @Test
  void nonTransactionalOperationHasNoCommitToWaitFor() {
    AtomicInteger calls = new AtomicInteger();
    assertThat(effects.submit("synthetic", calls::incrementAndGet)).isFalse();
    assertThat(calls).hasValue(1);
  }

  @Test
  void unsupportedActiveTransactionFailsClosedInsteadOfSendingEarly() {
    TransactionSynchronizationManager.setActualTransactionActive(true);
    AtomicInteger calls = new AtomicInteger();
    assertThatThrownBy(() -> effects.submit("synthetic", calls::incrementAndGet))
        .isInstanceOf(IllegalStateException.class);
    assertThat(calls).hasValue(0);
  }

  @Test
  void mailUsesImmutableSnapshotAndDispatchFailureNeverReachesCaller() {
    begin();
    MailDispatcher gateway = mock(MailDispatcher.class);
    var sender = new AfterCommitUserMailSender(gateway, effects);
    var metadata = new java.util.LinkedHashMap<String, String>();
    metadata.put("user", "Original");
    SendMailTemplate mail = new SendMailTemplate("synthetic@example.test", "welcome", metadata);
    sender.sendEmail(mail);
    metadata.put("user", "Changed");
    verifyNoInteractions(gateway);
    doThrow(new IllegalStateException("synthetic failure")).when(gateway).dispatch(any());
    assertThatCode(
            () ->
                TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit))
        .doesNotThrowAnyException();
    verify(gateway)
        .dispatch(
            argThat(
                snapshot ->
                    snapshot.to().equals("synthetic@example.test")
                        && snapshot.metadata().get("user").equals("Original")));
  }
}
