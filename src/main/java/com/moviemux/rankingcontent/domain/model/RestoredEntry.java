package com.moviemux.rankingcontent.domain.model;

import java.util.UUID;

public record RestoredEntry(UUID entryId, long version) {}
