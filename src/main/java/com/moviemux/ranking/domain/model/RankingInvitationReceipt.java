package com.moviemux.ranking.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Minimal receipt for the issuing account. The opaque ID is not an admission credential. */
public record RankingInvitationReceipt(UUID inviteId, OffsetDateTime expiresAt) {}
