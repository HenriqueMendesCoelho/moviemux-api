package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.ranking.domain.repository.RankingAuthorIdentities;
import com.moviemux.rankingcontent.domain.exception.*;
import com.moviemux.rankingcontent.domain.model.OpenedRatingCopyPreview;
import com.moviemux.rankingcontent.domain.model.OwnRoundRating;
import com.moviemux.rankingcontent.domain.model.RatingCopyContext;
import com.moviemux.rankingcontent.domain.port.ApplyOwnRatingCopy;
import com.moviemux.rankingcontent.domain.port.ReadOwnRatingCopyPreview;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class ApplyOwnRatingCopyPostgresAdapter implements ApplyOwnRatingCopy {
  private final NamedParameterJdbcTemplate jdbc;
  private final ReadOwnRatingCopyPreview preview;
  private final RankingAuthorIdentities authors;

  public ApplyOwnRatingCopyPostgresAdapter(NamedParameterJdbcTemplate jdbc, ReadOwnRatingCopyPreview preview,
      RankingAuthorIdentities authors) {
    this.jdbc=jdbc;
    this.preview=preview;
    this.authors=authors;
  }

  @Override public void requireTarget(UUID actorId,RatingCopyContext target) {
    if(jdbc.queryForList("""
        select r.id from ranking r
        join ranking_membership m on m.ranking_id=r.id and m.user_id=:actor and m.active
        join user_statistics s on s.user_id=m.user_id and s.consecutive_failed_login_attempts<10
        join ranking_movie_entry e on e.ranking_id=r.id and e.entry_id=:entry and e.removed_at is null
        join ranking_movie_round t on t.ranking_id=e.ranking_id and t.entry_id=e.entry_id and t.round_id=:round
        where r.id=:rank and r.archived_at is null and
          (r.owner_user_id=:actor or 2=(select count(*) from ranking_membership_grant g
            where g.ranking_id=r.id and g.user_id=:actor and g.capability in ('READ','RATE_OWN')))
        """,Map.of("actor",actorId,"rank",target.rankingId(),"entry",target.entryId(),"round",target.roundId()),
        UUID.class).isEmpty()) throw new RankingEntriesDeniedException();
  }

  @Override public OwnRoundRating apply(UUID actorId,OpenedRatingCopyPreview command,boolean enabled) {
    var pair=command.pair();
    var from=pair.source();
    var to=pair.destination();
    if (jdbc.queryForList("""
        select id from ranking where id in (:source,:target) order by id for update
        """,Map.of("source",from.rankingId(),"target",to.rankingId()),UUID.class).size()!=2)
      throw new RankingEntriesDeniedException();
    if (jdbc.queryForList("""
        select u.id from "user" u join user_statistics s on s.user_id=u.id
        where u.id=:actor and s.consecutive_failed_login_attempts<10 for key share of u for share of s
        """,Map.of("actor",actorId),UUID.class).isEmpty()) throw new RankingEntriesDeniedException();

    // Context, membership, actor-owned notes and canonical film are rechecked after both ranking locks.
    var state=preview.find(actorId,pair).orElseThrow(RankingEntriesDeniedException::new);
    if(!enabled) throw new RatingCopyUnavailableException();
    requireUnexpired(command.expiresAt());
    var expires=databaseDeadline(command.expiresAt());
    var found=jdbc.queryForList("""
        select c.operation_id from ranking_rating_copy_receipt c
        join ranking_author_identity a on a.ranking_id=c.ranking_id and a.author_id=c.author_id and a.account_id=:actor
        where c.operation_id=:operation and c.ranking_id=:rank and c.round_id=:round and c.expires_at=:expires
        for share of c
        """,Map.of("actor",actorId,"operation",command.operationId(),"rank",to.rankingId(),"round",to.roundId(),
            "expires",expires),UUID.class);
    if(!found.isEmpty()) {
      requireUnexpired(command.expiresAt());
      // The receipt represents the original outcome, not the current value after a later edit.
      return new OwnRoundRating(to.roundId(),command.source().score());
    }
    if(!command.source().equals(state.source())) throw new StaleRatingCopyException();
    if(state.destinationExists()) throw new DuplicateOwnRoundRatingException();

    var author=authors.getOrCreate(to.rankingId(),actorId);
    if(!to.rankingId().equals(author.rankingId()) || !actorId.equals(author.accountId()))
      throw new RankingEntriesDeniedException();
    var params=Map.of("rank",to.rankingId(),"round",to.roundId(),"author",author.authorId(),
        "score",command.source().score(),"operation",command.operationId(),"expires",expires);
    // Check the clock again immediately at write; rank locks also serialize ordinary rating writes.
    if(jdbc.update("""
        insert into ranking_movie_rating(ranking_id,round_id,author_id,score,created_at)
        select :rank,:round,:author,:score,clock_timestamp() where clock_timestamp()<:expires
        on conflict do nothing
        """,params)!=1) {
      requireUnexpired(command.expiresAt());
      throw new DuplicateOwnRoundRatingException();
    }
    jdbc.update("""
        insert into ranking_rating_copy_receipt(operation_id,ranking_id,round_id,author_id,expires_at)
        values (:operation,:rank,:round,:author,:expires)
        """,params);
    return new OwnRoundRating(to.roundId(),command.source().score());
  }

  private void requireUnexpired(Instant expiresAt) {
    Instant now=jdbc.queryForObject("select clock_timestamp()",Map.of(),OffsetDateTime.class).toInstant();
    if(!now.isBefore(expiresAt)) throw new StaleRatingCopyException();
  }

  private static OffsetDateTime databaseDeadline(Instant expiresAt) {
    // PostgreSQL has microsecond precision. Ceiling is exact over its discrete wall-clock instants.
    var micros=expiresAt.truncatedTo(ChronoUnit.MICROS);
    return (micros.equals(expiresAt)?micros:micros.plus(1,ChronoUnit.MICROS)).atOffset(ZoneOffset.UTC);
  }
}
