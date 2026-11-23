package com.moviemux.legacyboard.domain.repository;

/** Run a deferred legacy board effect only while a current phase lock is held. */
@FunctionalInterface
public interface LegacyBoardDispatch {
  void run(Runnable dispatch);
}
