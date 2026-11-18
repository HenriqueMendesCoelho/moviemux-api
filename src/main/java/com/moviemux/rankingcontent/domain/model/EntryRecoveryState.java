package com.moviemux.rankingcontent.domain.model;
import java.time.OffsetDateTime;
import java.util.UUID;
/** Operation-scoped state; no ratings, authors or counts. */
public record EntryRecoveryState(UUID entryId,long version,OffsetDateTime removedAt,OffsetDateTime expiresAt,UUID removalToken) {}
