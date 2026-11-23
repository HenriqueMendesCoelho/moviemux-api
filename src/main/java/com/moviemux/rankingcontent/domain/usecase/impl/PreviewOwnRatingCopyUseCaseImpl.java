package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.OwnRatingCopyPreviewResult;
import com.moviemux.rankingcontent.domain.model.OwnRatingCopyPreviewResult.Status;
import com.moviemux.rankingcontent.domain.model.RatingCopyContext;
import com.moviemux.rankingcontent.domain.model.RatingCopyPair;
import com.moviemux.rankingcontent.domain.port.ReadOwnRatingCopyPreview;
import com.moviemux.rankingcontent.domain.port.SealOwnRatingCopyPreview;
import com.moviemux.rankingcontent.domain.usecase.PreviewOwnRatingCopyUseCase;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class PreviewOwnRatingCopyUseCaseImpl implements PreviewOwnRatingCopyUseCase {
  private final ReadOwnRatingCopyPreview reader;
  private final SealOwnRatingCopyPreview sealer;

  public PreviewOwnRatingCopyUseCaseImpl(ReadOwnRatingCopyPreview reader, SealOwnRatingCopyPreview sealer) {
    this.reader = Objects.requireNonNull(reader, "reader");
    this.sealer = Objects.requireNonNull(sealer, "sealer");
  }

  @Override
  public OwnRatingCopyPreviewResult preview(RankingActor actor, RatingCopyPair pair) {
    if (actor == null || !actor.accountEnabled()) throw new RankingEntriesDeniedException();
    if (pair == null || !complete(pair.source()) || !complete(pair.destination()))
      throw new InvalidRatingCopyException();
    if (pair.source().rankingId().equals(pair.destination().rankingId())) throw new RankingEntriesDeniedException();
    var result = reader.find(actor.userId(), pair);
    if (result == null || result.isEmpty()) throw new RankingEntriesDeniedException();
    // Never turn missing configuration into an oracle for an inaccessible pair.
    sealer.requireConfigured();
    var snapshot = result.get();
    if (snapshot.destinationExists()) return blocked(Status.DESTINATION_EXISTS);
    var source = snapshot.source();
    if (source == null || source.score() == null || source.score() < 0 || source.score() > 10)
      return blocked(Status.SOURCE_INELIGIBLE);
    if (source.createdAt() == null) throw new RatingCopyUnavailableException();
    var issued = sealer.seal(actor.userId(), pair, source);
    if (issued == null || issued.token() == null || issued.token().isBlank() || issued.expiresAt() == null)
      throw new RatingCopyUnavailableException();
    return new OwnRatingCopyPreviewResult(Status.READY, source.score(), issued.token(), issued.expiresAt());
  }

  private boolean complete(RatingCopyContext context) {
    return context != null && context.rankingId() != null && context.entryId() != null && context.roundId() != null;
  }

  private OwnRatingCopyPreviewResult blocked(Status status) {
    return new OwnRatingCopyPreviewResult(status, null, null, null);
  }
}
