package com.moviemux.rankingcontent.domain.model;

import java.util.UUID;

/** Minimal anonymous summary; rankingName is nullable for legacy records. */
public record PublicRankingSummary(UUID rankingId, String rankingName) {}
