package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.model.RankingCapability;
import com.moviemux.ranking.domain.model.RosterMember;
import com.moviemux.ranking.domain.port.RankingMembershipRoster;
import com.moviemux.ranking.domain.port.ReplaceRankingMemberCapabilities;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class RankingMembershipRosterPostgresAdapter implements RankingMembershipRoster {
  private final NamedParameterJdbcTemplate jdbc;
  private final ReplaceRankingMemberCapabilities existingReplacement;

  public RankingMembershipRosterPostgresAdapter(NamedParameterJdbcTemplate jdbc,
      ReplaceRankingMemberCapabilities existingReplacement) {
    this.jdbc = jdbc;
    this.existingReplacement = existingReplacement;
  }

  @Override public List<RosterMember> find(UUID rankingId, UUID actorId, UUID after, int maxRows) {
    if (maxRows < 1 || maxRows > 51) throw new IllegalArgumentException("Invalid roster bound");
    return jdbc.query("""
        with owner_scope as (
          select r.id
          from ranking r
          join ranking_membership owners on owners.ranking_id=r.id and owners.user_id=:actor and owners.active
          join user_statistics auth on auth.user_id=owners.user_id and auth.consecutive_failed_login_attempts<10
          where r.id=:rank and r.owner_user_id=:actor and r.archived_at is null
        ), page as (
          select scope.id as ranking_id,member.user_id,member.membership_id,
                 case when position('@' in u.name)=0 then u.name end as display_name
          from owner_scope scope
          join ranking_membership member on member.ranking_id=scope.id and member.active and member.user_id<>:actor
          join "user" u on u.id=member.user_id
          where (cast(:after as uuid) is null or member.membership_id>cast(:after as uuid))
          order by member.membership_id limit :maxRows
        )
        select page.membership_id,page.display_name,g.capability
        from owner_scope scope
        left join page on page.ranking_id=scope.id
        left join ranking_membership_grant g on g.ranking_id=scope.id and g.user_id=page.user_id
        order by page.membership_id,g.capability
        """,new MapSqlParameterSource().addValue("rank",rankingId).addValue("actor",actorId)
        .addValue("after",after).addValue("maxRows",maxRows), rows -> {
      if (!rows.next()) throw new RankingAccessDeniedException();
      var result = new ArrayList<RosterMember>();
      boolean more = true;
      while (more) {
        var membership = rows.getObject("membership_id", UUID.class);
        if (membership == null) break; // Proven owner, zero matching members.
        String display = rows.getString("display_name");
        var grants = EnumSet.noneOf(RankingCapability.class);
        do {
          var grant = rows.getString("capability");
          if (grant != null) grants.add(RankingCapability.valueOf(grant));
          more = rows.next();
        } while (more && membership.equals(rows.getObject("membership_id", UUID.class)));
        result.add(new RosterMember(membership, safeDisplay(display), grants));
      }
      return List.copyOf(result);
    });
  }

  @Override public boolean replace(UUID rankingId, UUID actorId, UUID membershipId, Set<RankingCapability> capabilities) {
    var scope = Map.of("rank",rankingId,"actor",actorId,"membership",membershipId);
    // Existing mutations lock the ranking first. Keep the lock while resolving the handle and mutating grants.
    if (jdbc.queryForList("""
        select id from ranking where id=:rank and owner_user_id=:actor and archived_at is null for update
        """,scope,UUID.class).isEmpty()) return false;
    if (jdbc.queryForList("""
        select u.id from "user" u join user_statistics s on s.user_id=u.id
        join ranking_membership owners on owners.ranking_id=:rank and owners.user_id=u.id and owners.active
        where u.id=:actor and s.consecutive_failed_login_attempts<10
        for key share of u for share of s for update of owners
        """,scope,UUID.class).isEmpty()) return false;
    var target = jdbc.queryForList("""
        select user_id from ranking_membership
        where ranking_id=:rank and membership_id=:membership and user_id<>:actor and active for update
        """,scope,UUID.class);
    if (target.size()!=1) return false;

    // Same established whole-set replacement/rollback. The raw ID stays internal to this locked transaction.
    return existingReplacement.replace(rankingId,actorId,target.getFirst(),capabilities)
        == ReplaceRankingMemberCapabilities.Outcome.UPDATED;
  }

  private static String safeDisplay(String display) {
    if (display==null || display.codePoints().allMatch(cp->Character.isWhitespace(cp) || Character.isSpaceChar(cp)))
      return null;
    return display;
  }
}
