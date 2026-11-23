package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.PublicRankingEntry;
import com.moviemux.rankingcontent.domain.model.PublicRankingSummary;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReadPublicRankingProjection {
  Optional<PublicRankingSummary> summary(UUID rankingId);

  /**
   * Authorize live PUBLIC and select its nonremoved entries and revealed-only tallies in one
   * snapshot. Empty Optional hides an unavailable rank; a present empty list is a public page.
   */
  Optional<List<PublicRankingEntry>> entries(UUID rankingId, UUID after, int maxRows);
}
