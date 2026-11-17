package com.moviemux.rankingcontent.domain.port;
import com.moviemux.rankingcontent.domain.model.RankingMovieRoundListItem;
import java.util.*;
/** Empty optional means inaccessible; present empty list means authorized empty page. */
@FunctionalInterface
public interface RankingMovieRoundListPort {
  Optional<List<RankingMovieRoundListItem>> find(UUID actor, UUID ranking, UUID entry, Long afterNumber, int maxRows);
}
