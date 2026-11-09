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

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void remove(UUID id) {
    var target = users.prepare(id);
    if (target == null) return;
    movies.detach(target.ownedMovieIds());
    target.deleteAfterMoviesDetached();
  }
}
