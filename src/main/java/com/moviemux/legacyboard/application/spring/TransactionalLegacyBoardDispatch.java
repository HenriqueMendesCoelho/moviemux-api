package com.moviemux.legacyboard.application.spring;

import com.moviemux.legacyboard.domain.repository.LegacyBoardDispatch;
import com.moviemux.legacyboard.domain.repository.LegacyBoardInterlock;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TransactionalLegacyBoardDispatch implements LegacyBoardDispatch {
  private final LegacyBoardInterlock interlock;

  public TransactionalLegacyBoardDispatch(LegacyBoardInterlock interlock) { this.interlock = interlock; }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void run(Runnable dispatch) {
    // afterCommit does not hold the mutation transaction's DB locks. Obtain a fresh lock.
    interlock.requireLegacy();
    dispatch.run();
  }
}
