package com.moviemux.application.spring.bridge;

import com.moviemux.movie.domain.repository.MovieOwnershipRemoval;
import com.moviemux.user.domain.repository.AccountRemoval;
import com.moviemux.user.domain.repository.AccountRemovalPreparation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AccountRemovalBridge implements AccountRemoval {
  private final AccountRemovalPreparation users;
  private final MovieOwnershipRemoval movies;
  private final com.moviemux.wishlist.domain.repository.WishlistOwnershipRemoval wishlists;

  private final com.moviemux.movie.domain.repository.MovieAccountHistory movieHistory;
  private final com.moviemux.ranking.domain.repository.RankingAccountHistory rankingHistory;
  private final com.moviemux.ranking.domain.repository.RankingAccountInvitationCleanup rankingInvitations;

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void remove(UUID id) {
    var target = users.prepare(id);
    if (target == null) return;
    // prepare holds the user row lock; reference inserts must finish before these checks or wait.
    if (movieHistory.hasProtectedHistory(target.userId()) || rankingHistory.hasProtectedHistory(target.userId()))
      throw new com.moviemux.user.domain.exception.AccountDeletionRequiresHistoryMigrationException();
    rankingInvitations.removeReferencesTo(target.userId());
    movies.detach(target.userId());
    wishlists.removeOwnedBy(target.userId());
    target.deleteAfterMoviesDetached();
  }
}
