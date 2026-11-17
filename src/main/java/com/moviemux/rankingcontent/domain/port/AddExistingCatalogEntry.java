package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.AddedCatalogEntry;
import java.util.UUID;

/** Revalidate current ownership/grants/archive under locks; never create or refresh global catalog. */
@FunctionalInterface
public interface AddExistingCatalogEntry {
  AddedCatalogEntry write(UUID rankingId, UUID actorUserId, UUID movieId);
}
