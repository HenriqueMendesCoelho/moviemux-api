package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.model.RankingSummary;
import java.util.List;
import java.util.UUID;

public interface ListRankingSummaries {
  /** One current snapshot; authorize before UUID-ascending exclusive pagination, no global metadata. */
  List<RankingSummary> find(UUID actor, UUID after, int maxRows);
}
