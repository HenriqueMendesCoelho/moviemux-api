package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.RoundRevealView;
import java.util.Optional;
import java.util.UUID;

public interface ReadRoundAndRevealIfDue {
  /**
   * Read the exact scoped round under current authorization. Only an authorized due live round
   * with explicit false reveal state can transition. Empty means absent or inaccessible.
   */
  Optional<RoundRevealView> read(UUID rank, UUID entry, UUID round, UUID actor);
}
