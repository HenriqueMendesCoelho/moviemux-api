package com.moviemux.rankingcontent.domain.exception;

public final class RankingEntriesDeniedException extends RuntimeException {
  public RankingEntriesDeniedException() { super("Ranking entries access denied"); }
}
