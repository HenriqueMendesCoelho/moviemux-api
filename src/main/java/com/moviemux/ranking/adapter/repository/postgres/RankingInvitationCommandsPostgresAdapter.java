package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.exception.DuplicateRankingInvitationException;
import com.moviemux.ranking.domain.exception.RankingInvitationNotFoundException;
import com.moviemux.ranking.domain.model.RankingInvitationReceipt;
import com.moviemux.ranking.domain.port.RankingInvitationCommands;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class RankingInvitationCommandsPostgresAdapter implements RankingInvitationCommands {
  private final NamedParameterJdbcTemplate jdbc;

  private final RankingInvitationAddressProtection protection;

  public RankingInvitationCommandsPostgresAdapter(NamedParameterJdbcTemplate jdbc,
      RankingInvitationAddressProtection protection) {
    this.jdbc = jdbc;
    this.protection = protection;
  }

  @Override
  public RankingInvitationReceipt issue(UUID actor, UUID rankingId, String recipientCode) {
    var rank = lockRanking(rankingId);
    if (!rank.invitationMode() || !hasAuthority(actor, rankingId, rank) || !enabledNow(actor)) throw missing();
    // Resolve an untrusted candidate only for an authorized issuer. Do not lock an address before
    // the accounts: account deletion already holds the account and must be able to remove artifacts.
    byte[] digest=protection.digest(recipientCode);
    var invitee = jdbc.queryForList("""
        select account_id from ranking_invitation_address where lookup_digest=:digest
        """, Map.of("digest",digest), UUID.class).stream().findFirst()
        .orElseThrow(RankingInvitationCommandsPostgresAdapter::missing);
    if (!lockEnabledAccounts(actor,invitee) || hasMembership(rankingId,invitee,false)) throw missing();
    // Re-resolve the exact digest/account under a share lock. Rotation may have happened while waiting.
    if (jdbc.queryForList("""
        select account_id from ranking_invitation_address
        where lookup_digest=:digest and account_id=:account for share
        """, Map.of("digest",digest,"account",invitee),UUID.class).isEmpty()) throw missing();

    var target = Map.of("rank", rankingId, "target", invitee);
    // An expired, unconsumed slot can be replaced. Mark the terminal time without prolonging validity.
    jdbc.update("""
        update ranking_invitation set revoked_at=clock_timestamp()
        where ranking_id=:rank and invitee_user_id=:target
          and consumed_at is null and revoked_at is null and expires_at<=clock_timestamp()
        """, target);
    if (exists("""
        select exists(select 1 from ranking_invitation where ranking_id=:rank and invitee_user_id=:target
          and consumed_at is null and revoked_at is null)
        """, target)) throw new DuplicateRankingInvitationException();

    var result = jdbc.query("""
        with issued as (select clock_timestamp() as instant)
        insert into ranking_invitation(invite_id,ranking_id,invited_by_user_id,invitee_user_id,issued_at,expires_at)
        select :invite, :rank, :actor, :target, instant, instant+interval '168 hours' from issued
        returning invite_id,expires_at
        """, Map.of("invite", UUID.randomUUID(), "rank", rankingId, "actor", actor, "target", invitee),
        (row,index)->new RankingInvitationReceipt(row.getObject("invite_id", UUID.class),
            row.getObject("expires_at", OffsetDateTime.class)));
    return result.stream().findFirst().orElseThrow(RankingInvitationCommandsPostgresAdapter::missing);
  }

  @Override
  public void accept(UUID actor, UUID rankingId, UUID inviteId) {
    var rank = lockRanking(rankingId);
    var invite = invitation(rankingId,inviteId,false);
    if (!actor.equals(invite.invitee())) throw missing();
    if (invite.consumed()) {
      // Consumed replay never depends on current issuer eligibility and never recreates membership.
      if (!lockEnabledAccounts(actor) || !invite.equals(invitation(rankingId,inviteId,true))
          || !hasMembership(rankingId,actor,true)) throw missing();
      return;
    }
    if (!rank.invitationMode() || invite.revoked() || !hasAuthority(invite.issuer(),rankingId,rank)
        || !lockEnabledAccounts(actor,invite.issuer()) || hasMembership(rankingId,actor,false)
        || !invite.equals(invitation(rankingId,inviteId,true))) throw missing();
    var p = Map.of("rank", rankingId, "invite", inviteId, "actor", actor);
    // Database wall clock is evaluated after serialization. An expired invite cannot create membership.
    if (jdbc.update("""
        with accepted as (select clock_timestamp() as instant)
        update ranking_invitation set consumed_at=accepted.instant from accepted
        where ranking_id=:rank and invite_id=:invite and consumed_at is null and revoked_at is null
          and expires_at>accepted.instant
        """, p) != 1) throw missing();
    jdbc.update("""
        insert into ranking_membership(ranking_id,user_id,active,created_at)
        select ranking_id,invitee_user_id,true,consumed_at from ranking_invitation
        where ranking_id=:rank and invite_id=:invite
        """, p);
    jdbc.update("""
        insert into ranking_membership_grant(ranking_id,user_id,capability)
        values (:rank,:actor,'READ'), (:rank,:actor,'RATE_OWN')
        """, p);
  }

  @Override
  public void revoke(UUID actor, UUID rankingId, UUID inviteId) {
    var rank = lockRanking(rankingId);
    if (!hasAuthority(actor,rankingId,rank) || !lockEnabledAccounts(actor)) throw missing();
    var invite = invitation(rankingId,inviteId,true);
    if ((!rank.owner().equals(actor) && !invite.issuer().equals(actor)) || invite.consumed()) throw missing();
    if (!invite.revoked()) jdbc.update("""
        update ranking_invitation set revoked_at=clock_timestamp()
        where ranking_id=:rank and invite_id=:invite
        """, Map.of("rank", rankingId, "invite", inviteId));
  }

  private LockedRanking lockRanking(UUID rank) {
    return jdbc.query("""
        select owner_user_id, admission='INVITATION' as invitation_mode
        from ranking where id=:rank and archived_at is null for update
        """, Map.of("rank", rank), (row,index)->new LockedRanking(
            row.getObject("owner_user_id", UUID.class), row.getBoolean("invitation_mode")))
        .stream().findFirst().orElseThrow(RankingInvitationCommandsPostgresAdapter::missing);
  }

  private LockedInvitation invitation(UUID rank, UUID invite, boolean lock) {
    return jdbc.query("""
        select invited_by_user_id,invitee_user_id,consumed_at is not null as consumed,
          revoked_at is not null as revoked
        from ranking_invitation where ranking_id=:rank and invite_id=:invite
        """ + (lock ? " for update" : ""), Map.of("rank", rank, "invite", invite), (row,index)->new LockedInvitation(
            row.getObject("invited_by_user_id", UUID.class),row.getObject("invitee_user_id", UUID.class),
            row.getBoolean("consumed"),row.getBoolean("revoked")))
        .stream().findFirst().orElseThrow(RankingInvitationCommandsPostgresAdapter::missing);
  }

  private boolean hasAuthority(UUID actor, UUID ranking, LockedRanking rank) {
    return exists("""
        select exists(select 1 from ranking_membership m
          where m.ranking_id=:rank and m.user_id=:actor and m.active
            and (:owner or exists(select 1 from ranking_membership_grant g
              where g.ranking_id=m.ranking_id and g.user_id=m.user_id and g.capability='INVITE_MEMBER')))
        """, Map.of("rank", ranking, "actor", actor, "owner", rank.owner().equals(actor)));
  }

  private boolean hasMembership(UUID ranking, UUID actor, boolean activeOnly) {
    return exists("""
        select exists(select 1 from ranking_membership
          where ranking_id=:rank and user_id=:actor and (not :activeOnly or active))
        """, Map.of("rank", ranking, "actor", actor, "activeOnly", activeOnly));
  }

  private boolean enabledNow(UUID actor) {
    return exists("""
        select exists(select 1 from user_statistics
          where user_id=:actor and consecutive_failed_login_attempts<10)
        """,Map.of("actor",actor));
  }

  private boolean lockEnabledAccounts(UUID... ids) {
    var accounts=List.of(ids).stream().distinct().toList();
    return jdbc.queryForList("""
        select u.id from "user" u join user_statistics s on s.user_id=u.id
        where u.id in (:accounts) and s.consecutive_failed_login_attempts<10
        order by u.id for key share of u for share of s
        """, Map.of("accounts",accounts), UUID.class).size()==accounts.size();
  }

  private boolean exists(String query, Map<String,?> parameters) {
    return Boolean.TRUE.equals(jdbc.queryForObject(query, parameters, Boolean.class));
  }

  private static RankingInvitationNotFoundException missing() { return new RankingInvitationNotFoundException(); }

  private record LockedRanking(UUID owner, boolean invitationMode) {}
  private record LockedInvitation(UUID issuer, UUID invitee, boolean consumed, boolean revoked) {}
}
