package com.moviemux.rankingcontent.domain.port;

import com.moviemux.rankingcontent.domain.model.RecoverableEntry;
import java.util.List;
import java.util.UUID;

public interface ListRecoverableEntries {
  /** One current scope snapshot; filter restore authority and expiry before paging or selecting metadata. */
  List<RecoverableEntry> find(UUID actorId, UUID rankingId, UUID after, int maxRows);
}
