package com.moviemux.ranking.adapter.repository.postgres;

import com.moviemux.ranking.domain.exception.RankingAccessDeniedException;
import com.moviemux.ranking.domain.exception.RankingInvitationAddressUnavailableException;
import com.moviemux.ranking.domain.model.RankingInvitationAddress;
import com.moviemux.ranking.domain.port.CurrentRankingInvitationAddress;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RankingInvitationAddressPostgresAdapter implements CurrentRankingInvitationAddress {
  private final NamedParameterJdbcTemplate jdbc;
  private final RankingInvitationAddressProtection protection;
  private final SecureRandom random = new SecureRandom();

  public RankingInvitationAddressPostgresAdapter(NamedParameterJdbcTemplate jdbc,
      RankingInvitationAddressProtection protection) {
    this.jdbc = jdbc;
    this.protection = protection;
  }

  @Override
  public Optional<String> find(UUID actor) {
    protection.requireConfigured();
    return jdbc.queryForList("select encrypted_code from ranking_invitation_address where account_id=:actor",
        Map.of("actor", actor), byte[].class).stream().findFirst().map(value -> protection.decrypt(actor, value));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public String rotate(UUID actor) {
    jdbc.query("select pg_advisory_xact_lock(hashtextextended('moviemux.invitation.address:' || cast(:actor as text),0))",
        Map.of("actor", actor), rows -> { rows.next(); return null; });
    if (jdbc.queryForList("select id from \"user\" where id=:actor for key share",
        Map.of("actor", actor), UUID.class).isEmpty()) throw new RankingAccessDeniedException();
    String old = find(actor).orElse(null);
    for (int attempt=0; attempt<4; attempt++) {
      byte[] entropy = new byte[16];
      random.nextBytes(entropy);
      String code = RankingInvitationAddress.encode(entropy);
      if (code.equals(old)) continue;
      // Serialize generated candidate assignment across concurrent rotations; collisions are re-drawn.
      byte[] digest = protection.digest(code);
      var parameters = Map.of("actor", actor, "digest", digest);
      jdbc.query("select pg_advisory_xact_lock(hashtextextended('moviemux.invitation.code:' || encode(:digest,'hex'),0))",
          parameters, rows -> { rows.next(); return null; });
      if (!jdbc.queryForList("select account_id from ranking_invitation_address where lookup_digest=:digest",
          parameters, UUID.class).isEmpty()) continue;
      jdbc.update("""
          insert into ranking_invitation_address(account_id,lookup_digest,encrypted_code)
          values (:actor,:digest,:envelope)
          on conflict (account_id) do update set lookup_digest=excluded.lookup_digest,encrypted_code=excluded.encrypted_code
          """, Map.of("actor", actor, "digest", digest, "envelope", protection.encrypt(actor, code)));
      return code;
    }
    throw new RankingInvitationAddressUnavailableException();
  }
}
