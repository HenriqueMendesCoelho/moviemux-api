package com.moviemux.rankingcontent.domain.usecase.impl;

import com.moviemux.ranking.domain.model.RankingActor;
import com.moviemux.rankingcontent.domain.exception.InvalidRoundCommandException;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.RepeatedRoundReceipt;
import com.moviemux.rankingcontent.domain.port.RepeatedRoundOperations;
import com.moviemux.rankingcontent.domain.usecase.ManageRepeatedRoundsUseCase;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ManageRepeatedRoundsUseCaseImpl implements ManageRepeatedRoundsUseCase {
  private static final int MAX_DESCRIPTION_CODE_POINTS = 240;
  private final RepeatedRoundOperations rounds;

  public ManageRepeatedRoundsUseCaseImpl(RepeatedRoundOperations rounds) {
    this.rounds = Objects.requireNonNull(rounds, "rounds");
  }

  @Override
  @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
  public boolean settings(RankingActor actor, UUID rankingId) {
    return rounds.settings(rankingId, actorId(actor, rankingId));
  }

  @Override
  public boolean setSettings(RankingActor actor, UUID rankingId, boolean enabled) {
    return rounds.setSettings(rankingId, actorId(actor, rankingId), enabled);
  }

  @Override
  public RepeatedRoundReceipt create(RankingActor actor, UUID rankingId, UUID entryId, UUID commandId, String description) {
    UUID account = actorId(actor, rankingId);
    if (entryId == null) throw new RankingEntriesDeniedException();
    if (commandId == null) throw new InvalidRoundCommandException();
    var receipt = rounds.create(rankingId, entryId, account, commandId, canonical(description));
    if (receipt == null || receipt.roundId() == null || receipt.roundNumber() < 1)
      throw new RankingEntriesDeniedException();
    return receipt;
  }

  private UUID actorId(RankingActor actor, UUID rankingId) {
    if (actor == null || !actor.accountEnabled() || rankingId == null) throw new RankingEntriesDeniedException();
    return actor.userId();
  }

  private static String canonical(String description) {
    if (description == null) return null;
    int start = 0, end = description.length();
    while (start < end && whitespace(description.codePointAt(start))) start += Character.charCount(description.codePointAt(start));
    while (end > start && whitespace(description.codePointBefore(end))) end -= Character.charCount(description.codePointBefore(end));
    if (start == end) return null;
    if (description.codePointCount(start, end) > MAX_DESCRIPTION_CODE_POINTS)
      throw new InvalidRoundCommandException();
    String value = description.substring(start, end);
    if (value.codePoints().anyMatch(cp -> cp == 0 || cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE))
      throw new InvalidRoundCommandException();
    return value;
  }

  private static boolean whitespace(int cp) {
    return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0x85;
  }
}
