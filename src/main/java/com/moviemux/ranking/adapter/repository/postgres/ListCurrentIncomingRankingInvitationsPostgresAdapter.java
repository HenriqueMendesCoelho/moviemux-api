package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.model.IncomingRankingInvitation;
import com.moviemux.ranking.domain.port.ListCurrentIncomingRankingInvitations;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ListCurrentIncomingRankingInvitationsPostgresAdapter implements ListCurrentIncomingRankingInvitations {
  private final NamedParameterJdbcTemplate jdbc;

  public ListCurrentIncomingRankingInvitationsPostgresAdapter(NamedParameterJdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<IncomingRankingInvitation> find(UUID actor, UUID after, int maxRows) {
    Objects.requireNonNull(actor);
    if (maxRows < 1 || maxRows > 101) throw new IllegalArgumentException("Invalid query bound");
    return jdbc.query("""
        select i.invite_id,i.ranking_id,r.name as ranking_name,i.expires_at
        from ranking_invitation i
        join ranking r on r.id=i.ranking_id and r.archived_at is null and r.admission='INVITATION'
        join user_statistics recipient on recipient.user_id=i.invitee_user_id
            and recipient.consecutive_failed_login_attempts<10
        join user_statistics issuer on issuer.user_id=i.invited_by_user_id
            and issuer.consecutive_failed_login_attempts<10
        join ranking_membership issuing_member on issuing_member.ranking_id=i.ranking_id
            and issuing_member.user_id=i.invited_by_user_id and issuing_member.active
        where i.invitee_user_id=:actor
          and i.consumed_at is null and i.revoked_at is null and i.expires_at>statement_timestamp()
          and not exists (select 1 from ranking_membership target
                          where target.ranking_id=i.ranking_id and target.user_id=i.invitee_user_id)
          and (r.owner_user_id=i.invited_by_user_id or exists (
            select 1 from ranking_membership_grant g where g.ranking_id=i.ranking_id
            and g.user_id=i.invited_by_user_id and g.capability='INVITE_MEMBER'))
          and (cast(:after as uuid) is null or i.invite_id>cast(:after as uuid))
        order by i.invite_id limit :maxRows
        """, new MapSqlParameterSource().addValue("actor", actor).addValue("after", after)
            .addValue("maxRows", maxRows), (row, number) -> new IncomingRankingInvitation(
                row.getObject("invite_id", UUID.class), row.getObject("ranking_id", UUID.class), row.getString("ranking_name"),
                row.getObject("expires_at", OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC)));
  }
}
