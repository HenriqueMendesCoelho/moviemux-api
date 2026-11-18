package com.moviemux.rankingcontent.domain.model;

import java.util.UUID;

/** Own historical score, deliberately preserving nullable and out-of-range legacy values. */
public record OwnRoundRatingView(UUID roundId, Integer score) {}
