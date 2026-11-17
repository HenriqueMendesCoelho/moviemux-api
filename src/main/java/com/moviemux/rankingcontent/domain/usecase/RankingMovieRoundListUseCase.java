package com.moviemux.rankingcontent.domain.usecase;
import com.moviemux.ranking.domain.exception.*;
import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.model.*;
import com.moviemux.rankingcontent.domain.port.RankingMovieRoundListPort;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service
@Transactional(readOnly = true)
public class RankingMovieRoundListUseCase {
  private final RankingMovieRoundListPort port;
  public RankingMovieRoundListUseCase(RankingMovieRoundListPort port) { this.port = Objects.requireNonNull(port); }
  public RankingMovieRoundListPage execute(RankingActor actor, UUID ranking, UUID entry, Long afterNumber, int limit) {
    if (actor == null || !actor.accountEnabled() || ranking == null || entry == null) throw new RankingAccessDeniedException();
    if (limit < 1 || limit > 100 || (afterNumber != null && afterNumber < 1)) throw new InvalidRankingAccessPageException();
    var result = port.find(actor.userId(), ranking, entry, afterNumber, limit + 1);
    if (result == null) throw new RankingAccessDeniedException();
    var rows = result.orElseThrow(RankingAccessDeniedException::new);
    if (rows.size() > limit + 1) throw new RankingAccessDeniedException();
    long previous = afterNumber == null ? 0 : afterNumber;
    var ids = new HashSet<UUID>();
    for (var row : rows) {
      if (row == null || row.roundId() == null || row.createdAt() == null || row.number() <= previous || !ids.add(row.roundId()))
        throw new RankingAccessDeniedException();
      previous = row.number();
    }
    var items = rows.subList(0, Math.min(limit, rows.size()));
    return new RankingMovieRoundListPage(items, rows.size() > limit ? items.getLast().number() : null);
  }
}
