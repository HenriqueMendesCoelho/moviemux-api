package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.rankingcontent.domain.model.RatingCopyPair;
import com.moviemux.rankingcontent.domain.model.RatingCopyPreviewSnapshot;
import com.moviemux.rankingcontent.domain.model.RatingCopySourceVersion;
import com.moviemux.rankingcontent.domain.port.ReadOwnRatingCopyPreview;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReadOwnRatingCopyPreviewPostgresAdapter implements ReadOwnRatingCopyPreview {
  private final NamedParameterJdbcTemplate jdbc;

  public ReadOwnRatingCopyPreviewPostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

  @Override public Optional<RatingCopyPreviewSnapshot> find(UUID actor, RatingCopyPair pair) {
    // One snapshot resolves both scopes, current account, membership, grants and only the actor's notes.
    // The exact local movie ID prevents a title/provider-ID match from crossing content types or versions.
    return jdbc.query("""
        with contexts(rank_id,entry_id,round_id,destination) as (values
          (cast(:sourceRank as uuid),cast(:sourceEntry as uuid),cast(:sourceRound as uuid),false),
          (cast(:targetRank as uuid),cast(:targetEntry as uuid),cast(:targetRound as uuid),true)
        ), eligible as (
          select c.rank_id,c.round_id,c.destination,e.movie_id
          from contexts c
          join ranking r on r.id=c.rank_id and r.archived_at is null
          join ranking_membership m on m.ranking_id=r.id and m.user_id=:actor and m.active
          join user_statistics s on s.user_id=m.user_id and s.consecutive_failed_login_attempts < 10
          join ranking_movie_entry e on e.ranking_id=r.id and e.entry_id=c.entry_id and e.removed_at is null
          join ranking_movie_round t on t.ranking_id=e.ranking_id and t.entry_id=e.entry_id and t.round_id=c.round_id
          where r.owner_user_id=:actor
             or (exists(select 1 from ranking_membership_grant g
                   where g.ranking_id=r.id and g.user_id=:actor and g.capability='READ')
                 and (not c.destination or exists(select 1 from ranking_membership_grant g
                   where g.ranking_id=r.id and g.user_id=:actor and g.capability='RATE_OWN')))
        )
        select source_note.score, source_note.created_at, source_note.updated_at,
          target_note.author_id is not null as destination_exists
        from eligible source join eligible target
          on target.destination and not source.destination and target.movie_id=source.movie_id
            and target.rank_id<>source.rank_id
        left join ranking_author_identity source_author on source_author.ranking_id=source.rank_id and source_author.account_id=:actor
        left join ranking_movie_rating source_note
          on source_note.ranking_id=source.rank_id and source_note.round_id=source.round_id and source_note.author_id=source_author.author_id
        left join ranking_author_identity target_author on target_author.ranking_id=target.rank_id and target_author.account_id=:actor
        left join ranking_movie_rating target_note
          on target_note.ranking_id=target.rank_id and target_note.round_id=target.round_id and target_note.author_id=target_author.author_id
        """, Map.of("actor",actor,"sourceRank",pair.source().rankingId(),"sourceEntry",pair.source().entryId(),
            "sourceRound",pair.source().roundId(),"targetRank",pair.destination().rankingId(),
            "targetEntry",pair.destination().entryId(),"targetRound",pair.destination().roundId()),
        (row,index)->{
          Instant created=toInstant(row,"created_at");
          var source=created==null?null:new RatingCopySourceVersion(row.getObject("score",Integer.class),created,
              toInstant(row,"updated_at"));
          return new RatingCopyPreviewSnapshot(source,row.getBoolean("destination_exists"));
        }).stream().findFirst();
  }

  private static Instant toInstant(ResultSet row,String field) throws SQLException {
    var stamp=row.getObject(field,OffsetDateTime.class);
    return stamp==null?null:stamp.toInstant();
  }
}
