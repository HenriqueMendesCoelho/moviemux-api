package com.moviemux.adapter.core.effects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Best effort delivery only: callbacks are neither durable nor retried. */
@Component
public class AfterCommitEffects {
  private static final Logger LOG = LoggerFactory.getLogger(AfterCommitEffects.class);

  public boolean submit(String operation, Runnable effect) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      if (!TransactionSynchronizationManager.isSynchronizationActive()) {
        throw new IllegalStateException("Active transaction does not support after-commit effects");
      }
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              deliver(operation, effect);
            }
          });
      return true;
    }
    deliver(operation, effect);
    return false;
  }

  private void deliver(String operation, Runnable effect) {
    try {
      effect.run();
    } catch (Exception failure) {
      // Do not log recipient data, request bodies, reset keys, tokens or remote response bodies.
      LOG.warn(
          "Best effort {} failed after the local operation; no automatic retry ({})",
          operation,
          failure.getClass().getSimpleName());
    }
  }
}
