package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.RoundRevealView;
import com.moviemux.rankingcontent.domain.port.ReadRoundAndRevealIfDue;
import com.moviemux.rankingcontent.domain.usecase.ReadRankingRoundUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ReadRankingRoundUseCaseImpl implements ReadRankingRoundUseCase {
  private final ReadRoundAndRevealIfDue rounds;

  public ReadRankingRoundUseCaseImpl(ReadRoundAndRevealIfDue rounds) {
    this.rounds = Objects.requireNonNull(rounds, "rounds");
  }

  @Override
  public RoundRevealView read(RankingActor actor, UUID rank, UUID entry, UUID round) {
    if (actor == null || !actor.accountEnabled() || rank == null || entry == null || round == null)
      throw new RankingEntriesDeniedException();
    var found = rounds.read(rank, entry, round, actor.userId());
    if (found == null || found.isEmpty()) throw new RankingEntriesDeniedException();
    var view = found.get();
    if (!round.equals(view.roundId()) || view.roundNumber() < 1) throw new RankingEntriesDeniedException();
    return view;
  }
}
