package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.repository.RankingAccountInvitationCleanup;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RankingAccountInvitationCleanupPostgresAdapter implements RankingAccountInvitationCleanup {
  private final NamedParameterJdbcTemplate jdbc;

  public RankingAccountInvitationCleanupPostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void removeReferencesTo(UUID accountId) {
    var params=Map.of("account",accountId);
    // The bridge already holds the account row lock. Never acquire a ranking lock from here.
    jdbc.update("delete from ranking_invitation_address where account_id=:account",params);
    jdbc.update("""
        with related as materialized (
          select invite_id from ranking_invitation
          where invitee_user_id=:account or invited_by_user_id=:account
          order by invite_id for update
        )
        delete from ranking_invitation i using related
        where i.invite_id=related.invite_id
        """,params);
  }
}
