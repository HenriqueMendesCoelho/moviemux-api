package com.moviemux.transactioneffects;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.adapter.core.effects.AfterCommitEffects;
import com.moviemux.movie.adapter.notification.AfterCommitMovieNotifications;
import com.moviemux.movie.application.spring.MovieStatisticsCache;
import com.moviemux.movie.domain.model.NotificationNote;
import com.moviemux.movie.domain.repository.MovieNotificationRepository;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class NotificationCommitTest {
  @AfterEach
  void clear() {
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(false);
  }

  private void begin() {
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
  }

  @Test
  void socketSnapshotWaitsForCommitAndPreservesEmitterSpellingAtBoundary() {
    begin();
    var gateway = mock(MovieNotificationRepository.class);
    var notifications = new AfterCommitMovieNotifications(gateway, new AfterCommitEffects());
    UUID movie = UUID.randomUUID();
    NotificationNote payload = new NotificationNote(8, null, null, UUID.randomUUID(), "Synthetic");
    assertThat(notifications.notifyMovie(movie, "create-note", payload, "emitter"))
        .isEqualTo(MovieNotificationRepository.Delivery.DEFERRED);
    verifyNoInteractions(gateway);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    verify(gateway).notifyMovie(movie, "create-note", payload, "emitter");
  }

  @Test
  void skippedNotificationsDoNotRegisterCallbacks() {
    begin();
    var gateway = mock(MovieNotificationRepository.class);
    var notifications = new AfterCommitMovieNotifications(gateway, new AfterCommitEffects());
    assertThat(notifications.notifyMovie(null, "event", null, null))
        .isEqualTo(MovieNotificationRepository.Delivery.SKIPPED);
    assertThat(notifications.notifyMovie(UUID.randomUUID(), "  ", null, null))
        .isEqualTo(MovieNotificationRepository.Delivery.SKIPPED);
    assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    verifyNoInteractions(gateway);
  }

  @Test
  void immediateNotificationFailureHasExplicitOutcomeWithoutRetry() {
    var gateway = mock(MovieNotificationRepository.class);
    when(gateway.notifyMovie(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("synthetic"));
    var notifications = new AfterCommitMovieNotifications(gateway, new AfterCommitEffects());
    assertThat(notifications.notifyMovie(UUID.randomUUID(), "event", null, null))
        .isEqualTo(MovieNotificationRepository.Delivery.FAILED);
    verify(gateway, times(1)).notifyMovie(any(), any(), any(), any());
  }

  @Test
  void cacheRemainsIntactUntilCommitAndAfterRollback() {
    begin();
    var manager = new ConcurrentMapCacheManager("statistics");
    var cache = manager.getCache("statistics");
    cache.put("entry", "original");
    var invalidation = new MovieStatisticsCache(manager, new AfterCommitEffects());
    invalidation.invalidate();
    assertThat(cache.get("entry", String.class)).isEqualTo("original");
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(c -> c.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    assertThat(cache.get("entry", String.class)).isEqualTo("original");
    TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    begin();
    invalidation.invalidate();
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    assertThat(cache.get("entry")).isNull();
  }
}
