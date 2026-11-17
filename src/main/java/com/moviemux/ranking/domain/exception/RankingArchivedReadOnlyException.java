package com.moviemux.ranking.domain.exception;

public final class RankingArchivedReadOnlyException extends RuntimeException {
  public RankingArchivedReadOnlyException() { super("Ranking is archived and read only"); }
}
