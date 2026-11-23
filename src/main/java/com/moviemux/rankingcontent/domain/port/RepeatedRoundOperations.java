package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.RepeatedRoundReceipt;
import java.util.UUID;

public interface RepeatedRoundOperations {
  /** Both setting operations require the current active actual owner of a live rank. */
  boolean settings(UUID rankingId, UUID actorId);

  boolean setSettings(UUID rankingId, UUID actorId, boolean enabled);

  /** Create/replay under current ownership and context, with only new commands gated by setting. */
  RepeatedRoundReceipt create(UUID rankingId, UUID entryId, UUID actorId, UUID commandId,
      String canonicalDescription);
}
