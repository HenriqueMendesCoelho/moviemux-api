package com.moviemux.ranking.domain.port;

import com.moviemux.ranking.domain.model.RankingInvitationReceipt;
import java.util.UUID;

/**
 * Recheck all actor and invitee eligibility under transaction locks; authorization is current,
 * ranking-scoped and account-bound, never inferred from an invitation ID or global role.
 * No command dispatches notifications or grants access outside the selected ranking.
 */
public interface RankingInvitationCommands {
  /** Create a single live invitation without extending the expiry of an existing one. */
  RankingInvitationReceipt issue(UUID actor, UUID rankingId, String recipientCode);

  /** Consume and create membership plus default grants atomically; replay never reactivates. */
  void accept(UUID actor, UUID rankingId, UUID inviteId);

  /** Revoke a pending invitation; revocation cannot undo acceptance or change grants. */
  void revoke(UUID actor, UUID rankingId, UUID inviteId);
}
