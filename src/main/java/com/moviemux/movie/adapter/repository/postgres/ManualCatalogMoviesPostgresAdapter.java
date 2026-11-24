package com.moviemux.movie.adapter.repository.postgres;

import com.moviemux.movie.domain.exception.InvalidManualCatalogMovieException;
import com.moviemux.movie.domain.exception.ManualCatalogCommandConflictException;
import com.moviemux.movie.domain.exception.ManualCatalogForbiddenException;
import com.moviemux.movie.domain.model.ManualCanonicalMovie.Command;
import com.moviemux.movie.domain.model.ManualCanonicalMovie.Result;
import com.moviemux.movie.domain.repository.ManualCatalogMovies;
import com.moviemux.user.domain.model.Role;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static com.moviemux.movie.domain.model.ManualCanonicalMovie.FINGERPRINT_VERSION;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class ManualCatalogMoviesPostgresAdapter implements ManualCatalogMovies {
  private static final String AUTHORITY="""
      select u.id from "user" u
      join user_statistics state on state.user_id=u.id and state.consecutive_failed_login_attempts<10
      join user_roles role on role.user_id=u.id and role.roles=:admin
      where u.id=:actor
      """;
  private final NamedParameterJdbcTemplate jdbc;

  public ManualCatalogMoviesPostgresAdapter(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }

  @Override public boolean currentAdmin(UUID actorId) {
    return !jdbc.queryForList(AUTHORITY+" limit 1",authority(actorId),UUID.class).isEmpty();
  }

  @Override public Result create(UUID actorId, Command movie, String fingerprint) {
    // Lock the actual persisted authority, not merely an account whose roles could change concurrently.
    if (jdbc.queryForList(AUTHORITY+" for key share of u for share of state,role",authority(actorId),UUID.class).isEmpty())
      throw new ManualCatalogForbiddenException();
    var prior=receipts(movie.commandId());
    if(!prior.isEmpty())return replay(prior,fingerprint);
    if (!movie.genreIds().isEmpty() && jdbc.queryForList("""
        select id from movie_genre where id in (:genres) order by id for key share
        """,Map.of("genres",movie.genreIds()),Long.class).size()!=movie.genreIds().size())
      throw new InvalidManualCatalogMovieException();

    var parameters=new MapSqlParameterSource()
        .addValue("id",UUID.randomUUID()).addValue("command",movie.commandId())
        .addValue("version",FINGERPRINT_VERSION).addValue("fingerprint",fingerprint)
        .addValue("pt",movie.portugueseTitle()).addValue("en",movie.englishTitle()).addValue("original",movie.originalTitle())
        .addValue("director",movie.director()).addValue("description",movie.description())
        .addValue("released",movie.releaseDate()).addValue("runtime",movie.runtime());
    var inserted=jdbc.queryForList("""
        insert into movie(id,created_at,portuguese_title,english_title,original_title,director,description,
          release_date,runtime,legacy_eligible,manual_command_id,manual_command_version,manual_command_fingerprint)
        values(:id,clock_timestamp(),:pt,:en,:original,:director,:description,:released,:runtime,false,:command,:version,:fingerprint)
        on conflict (manual_command_id) do nothing returning id
        """,parameters,UUID.class);
    if (!inserted.isEmpty()) {
      UUID id=inserted.getFirst();
      for(var genre:movie.genreIds())
        jdbc.update("insert into movies_genres(movie_id,genre_id) values(:movie,:genre)",Map.of("movie",id,"genre",genre));
      return new Result(id,true);
    }

    // READ COMMITTED takes a fresh snapshot after unique-index wait; uncommitted intent cannot be replayed.
    return replay(receipts(movie.commandId()),fingerprint);
  }

  private List<Receipt> receipts(UUID command) {
    return jdbc.query("""
        select id, manual_command_version, manual_command_fingerprint
        from movie where manual_command_id=:command and not legacy_eligible
        """,Map.of("command",command),(rs,row)->new Receipt(rs.getObject("id",UUID.class),
            rs.getShort("manual_command_version"),rs.getString("manual_command_fingerprint")));
  }

  private Result replay(List<Receipt> existing,String fingerprint) {
    if (existing.size()!=1) throw new IllegalStateException("Manual movie command unavailable");
    var receipt=existing.getFirst();
    if (receipt.version()!=FINGERPRINT_VERSION || !fingerprint.equals(receipt.fingerprint()))
      throw new ManualCatalogCommandConflictException();
    return new Result(receipt.movieId(),false);
  }

  private static Map<String,Object> authority(UUID actorId){return Map.of("actor",actorId,"admin",Role.ADM.getCode());}
  private record Receipt(UUID movieId,short version,String fingerprint) {}
}
