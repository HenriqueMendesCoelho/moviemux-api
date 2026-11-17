package com.moviemux.ranking.domain.exception;

/** Deliberately carries no ranking existence, member or private resource information. */
public final class RankingAccessDeniedException extends RuntimeException {
  public RankingAccessDeniedException() { super("Ranking access denied"); }
}
