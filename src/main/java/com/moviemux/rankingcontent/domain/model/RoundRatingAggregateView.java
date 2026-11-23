package com.moviemux.rankingcontent.domain.model;

import java.math.BigInteger;
import java.util.UUID;

/** Exact disclosed-only aggregate. Hidden or unknown states carry no totals or counts. */
public record RoundRatingAggregateView(UUID roundId, Boolean revealed, BigInteger total, BigInteger votes) {}
