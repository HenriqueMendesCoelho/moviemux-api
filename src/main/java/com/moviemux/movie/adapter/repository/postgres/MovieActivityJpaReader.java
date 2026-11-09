package com.moviemux.movie.adapter.repository.postgres;

import com.moviemux.movie.domain.MovieNote;
import com.moviemux.movie.domain.MovieNoteKey;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface MovieActivityJpaReader extends Repository<MovieNote, MovieNoteKey> {
  interface ReviewRow {
    Integer getNote();

    Integer getRuntime();
  }

  @Query(
      "select n.note as note, n.movie.runtime as runtime from MovieNote n where n.user.id ="
          + " :userId")
  List<ReviewRow> reviews(@Param("userId") UUID userId);

  @Query("select m.id from Movie m where m.user.id = :userId")
  List<UUID> ownedMovies(@Param("userId") UUID userId);
}
