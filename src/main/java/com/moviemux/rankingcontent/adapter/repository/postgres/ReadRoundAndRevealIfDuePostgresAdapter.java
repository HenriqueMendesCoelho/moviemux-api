package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.rankingcontent.domain.model.RoundRevealView;
import com.moviemux.rankingcontent.domain.port.ReadRoundAndRevealIfDue;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReadRoundAndRevealIfDuePostgresAdapter implements ReadRoundAndRevealIfDue {
  private final NamedParameterJdbcTemplate jdbc;

  public ReadRoundAndRevealIfDuePostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override @Transactional(propagation = Propagation.MANDATORY)
  public Optional<RoundRevealView> read(UUID rank, UUID entry, UUID round, UUID actor) {
    var scope = Map.of("rank", rank, "entry", entry, "round", round, "actor", actor);
    // Do not acquire a ranking lock on behalf of an outsider. Authorization is
    // rechecked after acquiring that lock, using a fresh READ COMMITTED snapshot.
    var ranks = jdbc.query("""
        select r.archived_at is not null as archived
        from ranking r
        where r.id=:rank and exists (
          select 1 from ranking_membership m where m.ranking_id=r.id and m.user_id=:actor and m.active
            and (r.owner_user_id=:actor or exists (
              select 1 from ranking_membership_grant g where g.ranking_id=r.id and g.user_id=:actor and g.capability='READ')))
        for update of r
        """, scope, (row,index)->row.getBoolean("archived"));
    if (ranks.isEmpty()) return Optional.empty();
    if (jdbc.queryForList("""
        select m.user_id from ranking_membership m
        join ranking r on r.id=m.ranking_id
        join user_statistics s on s.user_id=m.user_id and s.consecutive_failed_login_attempts<10
        where m.ranking_id=:rank and m.user_id=:actor and m.active
          and (r.owner_user_id=:actor or exists (select 1 from ranking_membership_grant g
            where g.ranking_id=:rank and g.user_id=:actor and g.capability='READ'))
        for update of m for share of s
        """, scope, UUID.class).isEmpty()) return Optional.empty();

    var current = jdbc.query("""
        select t.round_id,t.round_number,t.revealed
        from ranking_movie_round t join ranking_movie_entry e
          on e.ranking_id=t.ranking_id and e.entry_id=t.entry_id and e.removed_at is null
        where t.ranking_id=:rank and t.entry_id=:entry and t.round_id=:round
        for update of e,t
        """, scope, (row,index)->new RoundRevealView(row.getObject("round_id",UUID.class),
            row.getLong("round_number"), row.getObject("revealed",Boolean.class))).stream().findFirst();

    if (current.isEmpty() || ranks.get(0) || !Boolean.FALSE.equals(current.get().revealed())) return current;
    int changed = jdbc.update("""
        update ranking_movie_round set revealed=true
        where ranking_id=:rank and entry_id=:entry and round_id=:round and revealed=false
          and clock_timestamp() >= created_at + interval '1800 seconds'
        """, scope);
    return changed == 1
        ? Optional.of(new RoundRevealView(current.get().roundId(), current.get().roundNumber(), true))
        : current;
  }
}
