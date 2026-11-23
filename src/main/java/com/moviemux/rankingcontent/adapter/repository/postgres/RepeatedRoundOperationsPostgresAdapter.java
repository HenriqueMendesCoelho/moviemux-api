package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.ranking.domain.repository.RankingAuthorIdentities;
import com.moviemux.rankingcontent.domain.exception.RankingEntriesDeniedException;
import com.moviemux.rankingcontent.domain.exception.RepeatRoundsDisabledException;
import com.moviemux.rankingcontent.domain.exception.RoundCommandConflictException;
import com.moviemux.rankingcontent.domain.model.RepeatedRoundReceipt;
import com.moviemux.rankingcontent.domain.port.RepeatedRoundOperations;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class RepeatedRoundOperationsPostgresAdapter implements RepeatedRoundOperations {
  private final NamedParameterJdbcTemplate jdbc;
  private final RankingAuthorIdentities authors;

  public RepeatedRoundOperationsPostgresAdapter(NamedParameterJdbcTemplate jdbc,RankingAuthorIdentities authors) {
    this.jdbc=jdbc;
    this.authors=authors;
  }

  @Override public boolean settings(UUID rankingId,UUID actorId) {
    return jdbc.queryForList("""
        select r.repeat_rounds_enabled from ranking r
        join ranking_membership m on m.ranking_id=r.id and m.user_id=:actor and m.active
        join user_statistics s on s.user_id=m.user_id and s.consecutive_failed_login_attempts<10
        where r.id=:rank and r.owner_user_id=:actor and r.archived_at is null
        """,Map.of("rank",rankingId,"actor",actorId),Boolean.class).stream().findFirst()
        .orElseThrow(RankingEntriesDeniedException::new);
  }

  @Override public boolean setSettings(UUID rankingId,UUID actorId,boolean enabled) {
    boolean current=lockEligibleOwner(rankingId,actorId);
    if (current!=enabled) jdbc.update("update ranking set repeat_rounds_enabled=:enabled where id=:rank",
        Map.of("rank",rankingId,"enabled",enabled));
    return enabled;
  }

  @Override public RepeatedRoundReceipt create(UUID rankingId,UUID entryId,UUID actorId,UUID commandId,String canonicalDescription) {
    boolean enabled=lockEligibleOwner(rankingId,actorId);
    var scope=Map.of("rank",rankingId,"entry",entryId,"actor",actorId,"command",commandId);
    if (jdbc.queryForList("""
        select entry_id from ranking_movie_entry
        where ranking_id=:rank and entry_id=:entry and removed_at is null for update
        """,scope,UUID.class).isEmpty()) throw new RankingEntriesDeniedException();

    byte[] fingerprint=fingerprint(canonicalDescription);
    var previous=jdbc.query("""
        select t.round_id,t.round_number,t.creation_input_fingerprint,coalesce(a.account_id=:actor,false) same_actor
        from ranking_movie_round t
        join ranking_author_identity a on a.ranking_id=t.ranking_id and a.author_id=t.creation_author_id
        where t.ranking_id=:rank and t.entry_id=:entry and t.creation_command_id=:command
        """,scope,(row,index)->new Prior(row.getObject("round_id",UUID.class),row.getLong("round_number"),
            row.getBytes("creation_input_fingerprint"),row.getBoolean("same_actor"))).stream().findFirst();
    if (previous.isPresent()) {
      var receipt=previous.get();
      if (!receipt.sameActor() || !MessageDigest.isEqual(fingerprint,receipt.input())) throw new RoundCommandConflictException();
      return new RepeatedRoundReceipt(receipt.id(),receipt.number(),false);
    }
    if (!enabled) throw new RepeatRoundsDisabledException();

    Long previousNumber=jdbc.queryForObject("""
        select max(round_number) from ranking_movie_round where ranking_id=:rank and entry_id=:entry
        """,scope,Long.class);
    if (previousNumber==null || previousNumber<1) throw new RankingEntriesDeniedException();
    final long next;
    try { next=Math.addExact(previousNumber,1); }
    catch (ArithmeticException overflow) { throw new DataIntegrityViolationException("Round number unavailable"); }

    var author=authors.getOrCreate(rankingId,actorId);
    if (!rankingId.equals(author.rankingId()) || !actorId.equals(author.accountId())) throw new RankingEntriesDeniedException();
    UUID round=UUID.randomUUID();
    jdbc.update("""
        insert into ranking_movie_round(ranking_id,entry_id,round_id,round_number,description,created_at,revealed,
            creation_command_id,creation_author_id,creation_input_fingerprint)
        values(:rank,:entry,:round,:number,:description,clock_timestamp(),false,:command,:author,:fingerprint)
        """,new MapSqlParameterSource().addValue("rank",rankingId).addValue("entry",entryId).addValue("round",round)
        .addValue("number",next).addValue("description",canonicalDescription).addValue("command",commandId)
        .addValue("author",author.authorId()).addValue("fingerprint",fingerprint));
    return new RepeatedRoundReceipt(round,next,true);
  }

  private boolean lockEligibleOwner(UUID rank,UUID actor) {
    var params=Map.of("rank",rank,"actor",actor);
    // Serialize with entry import/removal and admission before locking the enabled owner's account.
    var setting=jdbc.queryForList("""
        select repeat_rounds_enabled from ranking
        where id=:rank and owner_user_id=:actor and archived_at is null for update
        """,params,Boolean.class).stream().findFirst().orElseThrow(RankingEntriesDeniedException::new);
    if (jdbc.queryForList("""
        select u.id from "user" u join user_statistics s on s.user_id=u.id
        where u.id=:actor and s.consecutive_failed_login_attempts<10
        for key share of u for share of s
        """,params,UUID.class).isEmpty()) throw new RankingEntriesDeniedException();
    if (jdbc.queryForList("""
        select user_id from ranking_membership where ranking_id=:rank and user_id=:actor and active
        """,params,UUID.class).isEmpty()) throw new RankingEntriesDeniedException();
    return setting;
  }

  private static byte[] fingerprint(String canonicalDescription) {
    try {
      var digest=MessageDigest.getInstance("SHA-256");
      // The command format is versioned; an absent reason is distinct from any actual prose bytes.
      digest.update((byte)1);
      digest.update((byte)(canonicalDescription==null?0:1));
      if (canonicalDescription!=null) digest.update(canonicalDescription.getBytes(StandardCharsets.UTF_8));
      return digest.digest();
    } catch (NoSuchAlgorithmException unavailable) {
      throw new DataIntegrityViolationException("Round request unavailable");
    }
  }

  private record Prior(UUID id,long number,byte[] input,boolean sameActor) {}
}
