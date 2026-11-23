package com.moviemux.ranking.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Minimal, recipient-only projection. An invitation does not grant access to the ranking. */
public record IncomingRankingInvitation(UUID inviteId, UUID rankingId, String rankingName, OffsetDateTime expiresAt) {}
