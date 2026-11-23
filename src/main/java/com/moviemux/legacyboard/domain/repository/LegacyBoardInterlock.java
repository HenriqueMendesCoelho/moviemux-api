package com.moviemux.legacyboard.domain.repository;

/** Acquire the durable phase lock inside the caller's transaction before any board work. */
@FunctionalInterface
public interface LegacyBoardInterlock {
  void requireLegacy();
}
