package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.ranking.domain.repository.RankingAuthorIdentities;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.OwnRoundRating;
import com.moviemux.rankingcontent.domain.port.WriteOwnRoundRating;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes only the authenticated author's contextual rating; never changes revelation. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class WriteOwnRoundRatingPostgresAdapter implements WriteOwnRoundRating {
  private final NamedParameterJdbcTemplate jdbc;
  private final RankingAuthorIdentities authors;

  public WriteOwnRoundRatingPostgresAdapter(NamedParameterJdbcTemplate jdbc, RankingAuthorIdentities authors) {
    this.jdbc = Objects.requireNonNull(jdbc);
    this.authors = Objects.requireNonNull(authors);
  }

  @Override
  public OwnRoundRating create(UUID rankingId, UUID roundId, UUID actorUserId, int score) {
    return write(rankingId, roundId, actorUserId, score, true);
  }

  @Override
  public OwnRoundRating update(UUID rankingId, UUID roundId, UUID actorUserId, int score) {
    return write(rankingId, roundId, actorUserId, score, false);
  }

  private record LockedRanking(UUID owner, boolean archived) {}

  private OwnRoundRating write(UUID rankingId, UUID roundId, UUID actorUserId, int score, boolean creating) {
    if (score < 0 || score > 10) throw new InvalidOwnRoundRatingException();
    var params = new HashMap<String, Object>();
    params.put("ranking", Objects.requireNonNull(rankingId));
    params.put("round", Objects.requireNonNull(roundId));
    params.put("actor", Objects.requireNonNull(actorUserId));
    params.put("score", score);
    // Same lock order as membership/grant changes: authorization is re-read after waiting.
    var ranks = jdbc.query("select owner_user_id, archived_at from ranking where id=:ranking for update", params,
        (row, n) -> new LockedRanking(row.getObject("owner_user_id", UUID.class), row.getObject("archived_at") != null));
    if (ranks.isEmpty()) throw new RankingEntriesDeniedException();
    var members = jdbc.query("select active from ranking_membership where ranking_id=:ranking and user_id=:actor for update", params,
        (row, n) -> row.getBoolean("active"));
    if (members.isEmpty() || !members.get(0)) throw new RankingEntriesDeniedException();
    var rank = ranks.get(0);
    if (!rank.owner().equals(actorUserId) && !Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists(select 1 from ranking_membership_grant
        where ranking_id=:ranking and user_id=:actor and capability='RATE_OWN')
        """, params, Boolean.class))) throw new RankingEntriesDeniedException();
    if (rank.archived()) throw new ArchivedOwnRoundRatingException();
    var rounds = jdbc.query("""
        select r.round_id from ranking_movie_round r
        join ranking_movie_entry e on e.ranking_id=r.ranking_id and e.entry_id=r.entry_id
        where r.ranking_id=:ranking and r.round_id=:round and e.removed_at is null for update of e,r
        """, params, (row, n) -> row.getObject("round_id", UUID.class));
    if (rounds.isEmpty()) throw new RankingEntriesDeniedException();
    // Editing a missing rating must not allocate a new contextual identity.
    UUID authorId;
    if (creating) {
      var author = authors.getOrCreate(rankingId, actorUserId);
      if (!rankingId.equals(author.rankingId()) || !actorUserId.equals(author.accountId()))
        throw new RankingEntriesDeniedException();
      authorId = author.authorId();
    } else {
      var ids = jdbc.query("""
          select author_id from ranking_author_identity where ranking_id=:ranking and account_id=:actor
          """, params, (row, n) -> row.getObject("author_id", UUID.class));
      if (ids.isEmpty()) throw new RankingEntriesDeniedException();
      authorId = ids.get(0);
    }
    params.put("author", authorId);
    if (creating) {
      int inserted = jdbc.update("""
          insert into ranking_movie_rating(ranking_id,round_id,author_id,score,created_at)
          values(:ranking,:round,:author,:score,clock_timestamp()) on conflict do nothing
          """, params);
      if (inserted != 1) throw new DuplicateOwnRoundRatingException();
    } else {
      // Same-value retries do not alter updated_at. Historical null/outliers are only changed
      // when this author explicitly edits this exact rating, never normalized in bulk.
      int updated = jdbc.update("""
          update ranking_movie_rating set
          updated_at=case when score is distinct from :score then clock_timestamp() else updated_at end,
          score=:score where ranking_id=:ranking and round_id=:round and author_id=:author
          """, params);
      if (updated != 1) throw new RankingEntriesDeniedException();
    }
    return new OwnRoundRating(roundId, score);
  }
}
