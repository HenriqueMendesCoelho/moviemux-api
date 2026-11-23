package com.moviemux.ranking.domain.port;

import java.util.Optional;
import java.util.UUID;

/** Server-side own-address commands, never a resolver or directory. */
public interface CurrentRankingInvitationAddress {
  /** Read one current enabled account's code without generating, logging or mutating it. */
  Optional<String> find(UUID actor);

  /** Atomically replace the actor's active generation without touching issued invitations. */
  String rotate(UUID actor);
}
