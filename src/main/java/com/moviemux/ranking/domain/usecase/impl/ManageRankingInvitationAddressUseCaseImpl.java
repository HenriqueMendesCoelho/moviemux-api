package com.moviemux.ranking.domain.usecase.impl;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.exception.RankingInvitationAddressUnavailableException;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.ranking.domain.model.RankingInvitationAddress;
import com.moviemux.ranking.domain.port.CurrentRankingInvitationAddress;
import com.moviemux.ranking.domain.usecase.ManageRankingInvitationAddressUseCase;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManageRankingInvitationAddressUseCaseImpl implements ManageRankingInvitationAddressUseCase {
  private final CurrentRankingInvitationAddress addresses;

  public ManageRankingInvitationAddressUseCaseImpl(CurrentRankingInvitationAddress addresses) {
    this.addresses = Objects.requireNonNull(addresses, "addresses");
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
  public Optional<String> get(RankingActor actor) {
    var result = addresses.find(actorId(actor));
    if (result == null) throw new RankingInvitationAddressUnavailableException();
    return result.map(this::requireCanonical);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public String rotate(RankingActor actor) {
    return requireCanonical(addresses.rotate(actorId(actor)));
  }

  private UUID actorId(RankingActor actor) {
    if (actor == null || !actor.accountEnabled()) throw new RankingAccessDeniedException();
    return actor.userId();
  }

  private String requireCanonical(String code) {
    if (!RankingInvitationAddress.isCanonical(code)) throw new RankingInvitationAddressUnavailableException();
    return code;
  }
}
