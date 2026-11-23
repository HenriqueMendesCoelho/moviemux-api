package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.InvalidRatingCopyException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.BulkOwnRatingCopyPreview.*;
import com.moviemux.rankingcontent.domain.port.ReadOwnRatingsBulkPreview;
import com.moviemux.rankingcontent.domain.usecase.PreviewOwnRatingsBulkUseCase;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.moviemux.rankingcontent.domain.model.BulkOwnRatingCopyPreview.MAX_ITEMS;

@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class PreviewOwnRatingsBulkUseCaseImpl implements PreviewOwnRatingsBulkUseCase {
  private final ReadOwnRatingsBulkPreview reader;

  public PreviewOwnRatingsBulkUseCaseImpl(ReadOwnRatingsBulkPreview reader) {
    this.reader = Objects.requireNonNull(reader, "reader");
  }

  @Override public List<Result> preview(RankingActor actor, Batch batch) {
    if (actor == null || !actor.accountEnabled()) throw new RankingEntriesDeniedException();
    if (batch == null || batch.sourceRankingId() == null || batch.destinationRankingId() == null
        || batch.sourceRankingId().equals(batch.destinationRankingId()) || batch.items() == null
        || batch.items().isEmpty() || batch.items().size() > MAX_ITEMS
        || batch.items().stream().anyMatch(this::incomplete)) throw new InvalidRatingCopyException();

    var stable = new Batch(batch.sourceRankingId(), batch.destinationRankingId(), List.copyOf(batch.items()));
    var rows = reader.find(actor.userId(), stable);
    if (rows == null || rows.size() != stable.items().size()) throw new RankingEntriesDeniedException();
    var answer = new ArrayList<Result>(rows.size());
    for (int index = 0; index < rows.size(); index++) {
      var row = rows.get(index);
      if (row == null || row.index() != index) throw new RankingEntriesDeniedException();
      Integer score = row.ownSourceScore();
      if (!row.available()) answer.add(new Result(index, Status.UNAVAILABLE, null));
      else if (row.destinationExists()) answer.add(new Result(index, Status.DESTINATION_EXISTS, null));
      else if (score == null || score < 0 || score > 10) answer.add(new Result(index, Status.SOURCE_INELIGIBLE, null));
      else answer.add(new Result(index, Status.READY, score));
    }
    return List.copyOf(answer);
  }

  private boolean incomplete(Pairing pair) {
    return pair == null || pair.sourceEntryId() == null || pair.sourceRoundId() == null
        || pair.destinationEntryId() == null || pair.destinationRoundId() == null;
  }
}
