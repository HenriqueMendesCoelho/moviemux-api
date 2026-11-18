package com.moviemux.rankingcontent.domain.model;

import java.math.BigInteger;
import java.util.UUID;

/** Exact revealed-score numerator and denominator; no hidden-vote metadata or rounded average. */
public record EntryRatingAggregateView(UUID entryId, BigInteger total, BigInteger votes, boolean partial) {}
