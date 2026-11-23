package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.model.BulkOwnRatingCopyPreview.Batch;
import com.moviemux.rankingcontent.domain.model.BulkOwnRatingCopyPreview.Candidate;
import com.moviemux.rankingcontent.domain.port.ReadOwnRatingsBulkPreview;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import static com.moviemux.rankingcontent.domain.model.BulkOwnRatingCopyPreview.MAX_ITEMS;

@Repository
public class ReadOwnRatingsBulkPreviewPostgresAdapter implements ReadOwnRatingsBulkPreview {
  private final NamedParameterJdbcTemplate jdbc;

  public ReadOwnRatingsBulkPreviewPostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override public List<Candidate> find(UUID actorId, Batch batch) {
    if (batch.items().isEmpty() || batch.items().size() > MAX_ITEMS)
      throw new IllegalArgumentException("Invalid bulk preview bound");
    var params = new MapSqlParameterSource().addValue("actor", actorId)
        .addValue("sourceRank", batch.sourceRankingId()).addValue("targetRank", batch.destinationRankingId());
    var values = new StringJoiner(", ");
    for (int index = 0; index < batch.items().size(); index++) {
      var row = batch.items().get(index);
      // Only bounded internally generated parameter names shape the SQL; no submitted value enters its text.
      values.add("(cast(:i%d as integer),cast(:se%d as uuid),cast(:sr%d as uuid),cast(:te%d as uuid),cast(:tr%d as uuid))"
          .formatted(index, index, index, index, index));
      params.addValue("i"+index,index).addValue("se"+index,row.sourceEntryId())
          .addValue("sr"+index,row.sourceRoundId()).addValue("te"+index,row.destinationEntryId())
          .addValue("tr"+index,row.destinationRoundId());
    }
    // One statement, one MVCC snapshot. Match exact contextual pairs and movie IDs before reading own notes.
    return jdbc.query("""
        with permitted as (
          select r.id
          from ranking r
          join ranking_membership m on m.ranking_id=r.id and m.user_id=:actor and m.active
          join user_statistics s on s.user_id=m.user_id and s.consecutive_failed_login_attempts<10
          where r.id in (:sourceRank,:targetRank) and r.archived_at is null
            and (r.owner_user_id=:actor or (
              exists(select 1 from ranking_membership_grant g
                where g.ranking_id=r.id and g.user_id=:actor and g.capability='READ')
              and (r.id=:sourceRank or exists(select 1 from ranking_membership_grant g
                where g.ranking_id=r.id and g.user_id=:actor and g.capability='RATE_OWN'))
            ))
        ), authority as (
          select count(*)=2 as allowed from permitted
        ), submitted(position,source_entry,source_round,target_entry,target_round) as (
          values %s
        ), matched as (
          select c.position, sr.round_id as source_round, tr.round_id as target_round
          from authority a join submitted c on a.allowed
          join ranking_movie_entry se on se.ranking_id=:sourceRank and se.entry_id=c.source_entry and se.removed_at is null
          join ranking_movie_round sr on sr.ranking_id=se.ranking_id and sr.entry_id=se.entry_id and sr.round_id=c.source_round
          join ranking_movie_entry te on te.ranking_id=:targetRank and te.entry_id=c.target_entry
            and te.removed_at is null and te.movie_id=se.movie_id
          join ranking_movie_round tr on tr.ranking_id=te.ranking_id and tr.entry_id=te.entry_id and tr.round_id=c.target_round
        ), notes as (
          select p.position, occupancy.author_id is not null as destination_exists, own.score
          from matched p
          left join ranking_author_identity dest on dest.ranking_id=:targetRank and dest.account_id=:actor
          left join ranking_movie_rating occupancy
            on occupancy.ranking_id=dest.ranking_id and occupancy.round_id=p.target_round and occupancy.author_id=dest.author_id
          left join ranking_author_identity source
            on occupancy.author_id is null and source.ranking_id=:sourceRank and source.account_id=:actor
          left join ranking_movie_rating own
            on own.ranking_id=source.ranking_id and own.round_id=p.source_round and own.author_id=source.author_id
        )
        select a.allowed,c.position,n.position is not null as available,n.destination_exists,n.score
        from authority a left join submitted c on a.allowed left join notes n on n.position=c.position
        order by c.position
        """.formatted(values),params,rows->{
      var result = new ArrayList<Candidate>();
      while (rows.next()) {
        if (!rows.getBoolean("allowed")) throw new RankingEntriesDeniedException();
        result.add(new Candidate(rows.getInt("position"),rows.getBoolean("available"),
            rows.getBoolean("destination_exists"),rows.getObject("score",Integer.class)));
      }
      return List.copyOf(result);
    });
  }
}
