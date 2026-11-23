package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.model.IncomingRankingInvitation;
import java.util.List;
import java.util.UUID;

public interface ListCurrentIncomingRankingInvitations {
  /** Filter recipient and current usability in one snapshot, before exclusive UUID keyset paging. */
  List<IncomingRankingInvitation> find(UUID actor, UUID after, int maxRows);
}
