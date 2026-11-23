package com.moviemux.rankingcontent.domain.usecase;

import com.moviemux.rankingcontent.domain.model.PublicRankingEntriesPage;
import com.moviemux.rankingcontent.domain.model.PublicRankingSummary;
import java.util.UUID;

public interface ReadPublicRankingUseCase {
  int DEFAULT_LIMIT = 20;
  int MAX_LIMIT = 50;
  PublicRankingSummary summary(UUID rankingId);
  PublicRankingEntriesPage entries(UUID rankingId, UUID after, int limit);
}
