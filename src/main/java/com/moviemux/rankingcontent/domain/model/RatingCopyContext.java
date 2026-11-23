package com.moviemux.rankingcontent.domain.model;

import java.util.UUID;

public record RatingCopyContext(UUID rankingId, UUID entryId, UUID roundId) {}
