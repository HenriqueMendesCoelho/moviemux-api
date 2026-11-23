package com.moviemux.ranking.domain.repository;

import java.util.UUID;

/** Only in an authorized account-deletion transaction after locking the account and checking protected history. */
public interface RankingAccountInvitationCleanup {
  void removeReferencesTo(UUID accountId);
}
