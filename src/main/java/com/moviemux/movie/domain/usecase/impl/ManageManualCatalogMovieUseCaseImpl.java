package com.moviemux.movie.domain.usecase.impl;

import com.moviemux.movie.domain.exception.InvalidManualCatalogMovieException;
import com.moviemux.movie.domain.exception.ManualCatalogForbiddenException;
import com.moviemux.movie.domain.model.ManualCanonicalMovie.Admin;
import com.moviemux.movie.domain.model.ManualCanonicalMovie.Command;
import com.moviemux.movie.domain.model.ManualCanonicalMovie.Result;
import com.moviemux.movie.domain.repository.ManualCatalogMovies;
import com.moviemux.movie.domain.usecase.ManageManualCatalogMovieUseCase;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static com.moviemux.movie.domain.model.ManualCanonicalMovie.*;

@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ManageManualCatalogMovieUseCaseImpl implements ManageManualCatalogMovieUseCase {
  private final ManualCatalogMovies catalog;

  public ManageManualCatalogMovieUseCaseImpl(ManualCatalogMovies catalog) {
    this.catalog = Objects.requireNonNull(catalog,"catalog");
  }

  @Override @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
  public void requireCurrentAdmin(Admin actor) {
    if (!catalog.currentAdmin(actorId(actor))) throw new ManualCatalogForbiddenException();
  }

  @Override public Result create(Admin actor, Command request) {
    UUID account=actorId(actor);
    if (!catalog.currentAdmin(account)) throw new ManualCatalogForbiddenException();
    var command=normalize(request);
    return catalog.create(account,command,fingerprint(command));
  }

  private Command normalize(Command command) {
    if (command==null || command.commandId()==null || command.commandId().version()!=4 || command.commandId().variant()!=2
        || command.genreIds()==null || command.genreIds().size()>MAX_GENRES) throw new InvalidManualCatalogMovieException();
    String pt=text(command.portugueseTitle(),150,false),en=text(command.englishTitle(),150,false),
        original=text(command.originalTitle(),150,false);
    if (pt==null && en==null && original==null) throw new InvalidManualCatalogMovieException();
    var runtime=command.runtime();var date=command.releaseDate();
    if (runtime!=null && (runtime<1 || runtime>999) || date!=null && (date.getYear()<1 || date.getYear()>9999))
      throw new InvalidManualCatalogMovieException();
    var unique=new HashSet<Long>();
    for (var id:command.genreIds())
      if (id==null || id<1 || id>MAX_SAFE_ID || !unique.add(id)) throw new InvalidManualCatalogMovieException();
    return new Command(command.commandId(),pt,en,original,text(command.director(),150,false),
        text(command.description(),4000,true),date,runtime,unique.stream().sorted().toList());
  }

  private String text(String value, int maximum, boolean newlines) {
    if (value==null) return null;
    int start=0,end=value.length();
    while(start<end && space(value.codePointAt(start))) start+=Character.charCount(value.codePointAt(start));
    while(end>start && space(value.codePointBefore(end))) end-=Character.charCount(value.codePointBefore(end));
    String trimmed=value.substring(start,end);
    if (trimmed.isEmpty() || trimmed.codePointCount(0,trimmed.length())>maximum
        || trimmed.codePoints().anyMatch(cp->Character.getType(cp)==Character.SURROGATE
            || Character.isISOControl(cp) && !(newlines && (cp=='\r' || cp=='\n'))))
      throw new InvalidManualCatalogMovieException();
    return trimmed;
  }

  private static boolean space(int cp){ return Character.isWhitespace(cp) || Character.isSpaceChar(cp); }

  private static UUID actorId(Admin actor) {
    if (actor==null || actor.userId()==null || !actor.enabled()) throw new ManualCatalogForbiddenException();
    return actor.userId();
  }

  private String fingerprint(Command command) {
    try {
      var digest=MessageDigest.getInstance("SHA-256");
      feed(digest,"moviemux:manual-catalog:v"+FINGERPRINT_VERSION);
      feed(digest,command.portugueseTitle());feed(digest,command.englishTitle());feed(digest,command.originalTitle());
      feed(digest,command.director());feed(digest,command.description());
      feed(digest,command.releaseDate()==null?null:command.releaseDate().toString());
      feed(digest,command.runtime()==null?null:command.runtime().toString());
      feed(digest,Integer.toString(command.genreIds().size()));
      for (var genre:command.genreIds()) feed(digest,genre.toString());
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("Manual movie hash unavailable",impossible);
    }
  }

  private static void feed(MessageDigest digest,String value) {
    byte[] bytes=value==null?null:value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes==null?-1:bytes.length).array());
    if (bytes!=null)digest.update(bytes);
  }
}
