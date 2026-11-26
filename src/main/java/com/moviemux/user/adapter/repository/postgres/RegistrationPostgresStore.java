package com.moviemux.user.adapter.repository.postgres;

import com.moviemux.user.domain.exception.InviteNotValidException;
import com.moviemux.user.domain.model.Role;
import com.moviemux.user.domain.registration.RegistrationFailure;
import com.moviemux.user.domain.registration.RegistrationGate;
import com.moviemux.user.domain.registration.RegistrationMode;
import com.moviemux.user.domain.registration.RegistrationWaitlist.*;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.moviemux.user.domain.registration.RegistrationFailure.Reason.*;

/** All mutations run behind the instance singleton lock, including final account creation. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class RegistrationPostgresStore implements RegistrationGate {
  private final JdbcTemplate jdbc;
  private final SecureRandom random = new SecureRandom();

  public RegistrationPostgresStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  private Policy policy(boolean lock) {
    try {
      var rows = jdbc.query("select mode,bootstrap_completed from instance_registration_policy where singleton"
          + (lock ? " for update" : ""), (rs, n) -> new Policy(
          RegistrationMode.valueOf(rs.getString(1)), rs.getBoolean(2)));
      if (rows.size()!=1) throw new RegistrationFailure(UNAVAILABLE);
      return rows.getFirst();
    } catch (org.springframework.dao.DataAccessException | IllegalArgumentException unavailable) {
      throw new RegistrationFailure(UNAVAILABLE);
    }
  }

  public RegistrationMode mode() { return policy(false).mode(); }

  @Override public Decision authorize(String email, String code) throws InviteNotValidException {
    var policy = policy(true);
    boolean bootstrap = !policy.bootstrapCompleted()
        && Boolean.FALSE.equals(jdbc.queryForObject("select exists(select 1 from \"user\")",Boolean.class));
    boolean supplied = code != null && !code.isBlank();
    if (!bootstrap && (supplied || policy.mode()!=RegistrationMode.OPEN)) {
      if (!supplied || jdbc.queryForList("""
          delete from user_invites i where i.code=?
            and not exists(select 1 from registration_waitlist w where w.invite_id=i.id and w.email<>?)
          returning i.id
          """,Long.class, code, normalize(email)).size()!=1) throw new InviteNotValidException();
    }
    return new Decision(policy.mode(), bootstrap);
  }

  @Override public boolean existingEmail(String email) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        select exists(select 1 from "user" where lower(email)=lower(?))
        """, Boolean.class,email));
  }

  @Override public void complete(String email, Decision decision) {
    jdbc.update("update instance_registration_policy set bootstrap_completed=true where singleton");
    jdbc.update("""
        delete from user_invites where id in (select invite_id from registration_waitlist where email=?)
        """,normalize(email));
    jdbc.update("delete from registration_waitlist where email=?",normalize(email));
  }

  public RegistrationMode adminMode(UUID actor) {
    requireAdmin(actor); return policy(false).mode();
  }

  public RegistrationMode changeMode(UUID actor, RegistrationMode mode) {
    policy(true);
    requireAdmin(actor);
    jdbc.update("update instance_registration_policy set mode=?,bootstrap_completed=true where singleton",mode.name());
    return mode;
  }

  public void request(String email) {
    if (policy(true).mode()!=RegistrationMode.WAITLIST) throw new RegistrationFailure(MODE_CHANGED);
    if (!existingEmail(email)) jdbc.update("""
        insert into registration_waitlist(id,email) values(?,?) on conflict(email) do nothing
        """, UUID.randomUUID(),email);
  }

  public Page list(UUID actor, UUID after, int limit) {
    requireAdmin(actor);
    var items=jdbc.query("""
        select id,email,requested_at,case
          when status<>'PENDING' and invite_id is null then 'REVOKED'
          when status='SENDING' and attempt_started_at<=clock_timestamp()-interval '5 minutes' then 'DELIVERY_UNKNOWN'
          else status end as current_status
        from registration_waitlist where (cast(? as uuid) is null or id>?)
        order by id limit ?
        """,(rs,n)->new Item(rs.getObject(1,UUID.class),rs.getString(2),
        rs.getObject(3,OffsetDateTime.class),Status.valueOf(rs.getString(4))),after,after,limit+1);
    boolean more=items.size()>limit;
    List<Item> page=List.copyOf(items.subList(0,Math.min(items.size(),limit)));
    return new Page(page, more?page.getLast().requestId():null);
  }

  public Claim claim(UUID actor, UUID id, boolean mailConfigured) {
    policy(true); requireAdmin(actor);
    var rows=jdbc.query("""
        select w.email,w.invite_id,i.code,w.status,
          coalesce(w.attempt_started_at>clock_timestamp()-interval '5 minutes',false)
        from registration_waitlist w left join user_invites i on i.id=w.invite_id where w.id=?
        """,(rs,n)->new Existing(rs.getString(1),rs.getObject(2,Long.class),rs.getString(3),
        Status.valueOf(rs.getString(4)),rs.getBoolean(5)),id);
    if(rows.size()!=1)throw new RegistrationFailure(REQUEST_NOT_FOUND);
    var row=rows.getFirst();
    if(row.status()!=Status.PENDING && row.code()==null)throw new RegistrationFailure(INVITATION_REVOKED);
    if(row.status()==Status.SENDING && row.inProgress())throw new RegistrationFailure(INVITATION_IN_PROGRESS);
    String code=row.code();
    if(row.inviteId()!=null && jdbc.queryForList(
        "select id from user_invites where id=? for key share",Long.class,row.inviteId()).isEmpty())
      throw new RegistrationFailure(INVITATION_REVOKED);
    if(row.status()==Status.GATEWAY_ACCEPTED)
      return new Claim(id,row.email(),code,null,row.status(),false);

    if (code==null) {
      for(int attempt=0;attempt<16;attempt++){
        String candidate="KB-"+(10_000_000+random.nextInt(989_999_999));
        var ids=jdbc.queryForList("insert into user_invites(code) values(?) on conflict(code) do nothing returning id",
            Long.class,candidate);
        if(!ids.isEmpty()){
          jdbc.update("update registration_waitlist set invite_id=? where id=?",ids.getFirst(),id);
          code=candidate;break;
        }
      }
      if(code==null)throw new RegistrationFailure(UNAVAILABLE);
    }

    if (!mailConfigured) {
      // An earlier unknown attempt must not be rewritten as definitively unavailable.
      Status status=(row.status()==Status.DELIVERY_UNKNOWN || row.status()==Status.SENDING)
          ?Status.DELIVERY_UNKNOWN:Status.DELIVERY_UNAVAILABLE;
      jdbc.update("update registration_waitlist set status=?,attempt_id=null,attempt_started_at=null where id=?", status.name(),id);
      return new Claim(id,row.email(),code,null,status,status==Status.DELIVERY_UNKNOWN);
    }
    UUID attempt=UUID.randomUUID();
    jdbc.update("""
        update registration_waitlist set status='SENDING',attempt_id=?,attempt_started_at=clock_timestamp() where id=?
        """,attempt,id);
    return new Claim(id,row.email(),code,attempt,Status.SENDING,
        row.status()==Status.DELIVERY_UNKNOWN || row.status()==Status.SENDING);
  }

  public Invitation finish(Claim claim, Status status) {
    policy(true);
    int changed=jdbc.update("""
        update registration_waitlist set status=?,attempt_id=null,attempt_started_at=null
        where id=? and invite_id is not null and attempt_id=? and status='SENDING'
        """,status.name(),claim.requestId(),claim.attemptId());
    if(changed!=1){
      var current=jdbc.query("select invite_id from registration_waitlist where id=?",
          (rs,n)->rs.getObject(1,Long.class),claim.requestId());
      if(current.isEmpty())throw new RegistrationFailure(REQUEST_NOT_FOUND);
      if(current.getFirst()==null)throw new RegistrationFailure(INVITATION_REVOKED);
      throw new RegistrationFailure(INVITATION_IN_PROGRESS);
    }
    return new Invitation(claim.requestId(),status,claim.code());
  }

  private void requireAdmin(UUID actor) {
    if(actor==null || jdbc.queryForList("""
        select u.id from "user" u
        join user_statistics state on state.user_id=u.id and state.consecutive_failed_login_attempts<10
        join user_roles role on role.user_id=u.id and role.roles=?
        where u.id=? for key share of u for share of state,role
        """,UUID.class,Role.ADM.getCode(),actor).isEmpty())throw new RegistrationFailure(FORBIDDEN);
  }

  public static String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
  public record Claim(UUID requestId,String email,String code,UUID attemptId,Status status,boolean priorUnknown) {}
  private record Policy(RegistrationMode mode,boolean bootstrapCompleted) {}
  private record Existing(String email,Long inviteId,String code,Status status,boolean inProgress) {}
}
