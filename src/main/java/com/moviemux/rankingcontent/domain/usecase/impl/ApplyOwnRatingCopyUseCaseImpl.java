package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.InvalidRatingCopyException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.exception.RatingCopyUnavailableException;
import com.moviemux.rankingcontent.domain.model.OpenedRatingCopyPreview;
import com.moviemux.rankingcontent.domain.model.OwnRoundRating;
import com.moviemux.rankingcontent.domain.model.RatingCopyContext;
import com.moviemux.rankingcontent.domain.port.ApplyOwnRatingCopy;
import com.moviemux.rankingcontent.domain.port.CopyApplyAvailability;
import com.moviemux.rankingcontent.domain.port.OpenOwnRatingCopyPreview;
import com.moviemux.rankingcontent.domain.usecase.ApplyOwnRatingCopyUseCase;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ApplyOwnRatingCopyUseCaseImpl implements ApplyOwnRatingCopyUseCase {
  private static final int TOKEN_LENGTH = 264;
  private static final Pattern TOKEN_ALPHABET = Pattern.compile("[A-Za-z0-9_-]{264}");
  private final OpenOwnRatingCopyPreview opener;
  private final ApplyOwnRatingCopy writes;
  private final CopyApplyAvailability availability;

  public ApplyOwnRatingCopyUseCaseImpl(OpenOwnRatingCopyPreview opener, ApplyOwnRatingCopy writes,
      CopyApplyAvailability availability) {
    this.opener = Objects.requireNonNull(opener, "opener");
    this.writes = Objects.requireNonNull(writes, "writes");
    this.availability = Objects.requireNonNull(availability, "availability");
  }

  @Override
  public OwnRoundRating apply(RankingActor actor, RatingCopyContext target, String token) {
    if (actor == null || !actor.accountEnabled()) throw new RankingEntriesDeniedException();
    if (!complete(target) || token == null || token.length() != TOKEN_LENGTH || !TOKEN_ALPHABET.matcher(token).matches())
      throw new InvalidRatingCopyException();
    OpenedRatingCopyPreview command;
    try {
      command = opener.open(token);
    } catch (RatingCopyUnavailableException unavailable) {
      // Check independent target authority without disclosing its existence to a denied actor.
      writes.requireTarget(actor.userId(), target);
      throw new RatingCopyUnavailableException();
    }
    if (command == null || command.actorId() == null || command.pair() == null
        || !complete(command.pair().destination())) throw new InvalidRatingCopyException();
    if (!actor.userId().equals(command.actorId()) || !target.equals(command.pair().destination()))
      throw new RankingEntriesDeniedException();
    var sourceContext = command.pair().source();
    if (!complete(sourceContext) || command.operationId() == null || command.expiresAt() == null)
      throw new InvalidRatingCopyException();
    if (sourceContext.rankingId().equals(target.rankingId())) throw new RankingEntriesDeniedException();
    var source = command.source();
    if (source == null || source.createdAt() == null || source.score() == null || source.score() < 0 || source.score() > 10)
      throw new InvalidRatingCopyException();

    // The adapter checks current source/destination authority before acting on this flag.
    var result = writes.apply(actor.userId(), command, availability.available());
    if (result == null || !target.roundId().equals(result.roundId()) || result.score() != source.score())
      throw new RankingEntriesDeniedException();
    return result;
  }

  private boolean complete(RatingCopyContext context) {
    return context != null && context.rankingId() != null && context.entryId() != null && context.roundId() != null;
  }
}
