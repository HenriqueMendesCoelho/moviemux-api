package com.moviemux.rankingcontent.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record RecoverableEntry(UUID entryId, String title, OffsetDateTime removedAt, OffsetDateTime expiresAt,
    long version) {}
