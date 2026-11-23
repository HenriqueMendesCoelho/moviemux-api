package com.moviemux.rankingcontent.adapter.repository.postgres;

import com.moviemux.rankingcontent.domain.exception.CatalogIdentityConflictException;
import com.moviemux.rankingcontent.domain.model.AddedCatalogEntry;
import com.moviemux.rankingcontent.domain.model.TmdbMovieCandidate;
import com.moviemux.rankingcontent.domain.port.ImportTmdbCatalogEntry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ImportTmdbCatalogEntryPostgresAdapter implements ImportTmdbCatalogEntry {
  private final NamedParameterJdbcTemplate jdbc;
  private final AddExistingCatalogEntryPostgresAdapter entries;

  public ImportTmdbCatalogEntryPostgresAdapter(NamedParameterJdbcTemplate jdbc,
      AddExistingCatalogEntryPostgresAdapter entries) {
    this.jdbc = jdbc;
    this.entries = entries;
  }

  @Override public Optional<AddedCatalogEntry> writeExisting(UUID rank, UUID actor, long tmdbId) {
    entries.requireAdd(rank, actor);
    return lookup(tmdbId).map(movie -> entries.linkLocked(rank, actor, movie));
  }

  @Override public AddedCatalogEntry writeFetched(UUID rank, UUID actor, TmdbMovieCandidate candidate) {
    entries.requireAdd(rank, actor);
    var found = lookup(candidate.tmdbId());
    if (found.isPresent()) return entries.linkLocked(rank, actor, found.get());

    UUID newId = UUID.randomUUID();
    // Movie is the canonical table in this installation. A second importer winning either
    // unique constraint never triggers a refresh, metadata overwrite, or legacy promotion.
    jdbc.update("""
        insert into movie(id,created_at,tmdb_id,imdb_id,portuguese_title,english_title,
          original_title,director,url_image,portuguese_url_trailer,english_url_trailer,
          description,release_date,runtime,legacy_eligible)
        values (:id,clock_timestamp(),:tmdb,:imdb,:pt,:en,:original,:director,:image,:ptTrailer,
          :enTrailer,:description,:release,:runtime,false)
        on conflict do nothing
        """, new MapSqlParameterSource().addValue("id", newId).addValue("tmdb", candidate.tmdbId())
        .addValue("imdb", candidate.imdbId()).addValue("pt", candidate.portugueseTitle())
        .addValue("en", candidate.englishTitle()).addValue("original", candidate.originalTitle())
        .addValue("director", candidate.director()).addValue("image", candidate.urlImage())
        .addValue("ptTrailer", candidate.portugueseUrlTrailer()).addValue("enTrailer", candidate.englishUrlTrailer())
        .addValue("description", candidate.description()).addValue("release", candidate.releaseDate())
        .addValue("runtime", candidate.runtime()));
    // A fresh snapshot after conflict resolves the TMDB winner. An IMDb conflict on a
    // different canonical row produces only an identity error, never an automatic merge.
    UUID canonical = lookup(candidate.tmdbId()).orElseThrow(CatalogIdentityConflictException::new);
    return entries.linkLocked(rank, actor, canonical);
  }

  private Optional<UUID> lookup(long tmdbId) {
    return jdbc.queryForList("select id from movie where tmdb_id=:tmdb for key share",
        new MapSqlParameterSource("tmdb", tmdbId), UUID.class).stream().findFirst();
  }
}
