package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.RestoredEntry;
import java.util.UUID;

public interface RestoreCurrentEntry {
  /** Recheck scope, exact pending version and live expiry under the normal recovery locks. */
  RestoredEntry restoreCurrent(UUID rankingId, UUID entryId, UUID actorId, long expectedVersion);
}
