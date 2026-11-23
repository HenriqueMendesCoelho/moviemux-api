package com.moviemux.rankingcontent.domain.model;

import java.util.UUID;

/** Minimal contextual round detail, without votes, authors, or reveal timing. */
public record RoundRevealView(UUID roundId, long roundNumber, Boolean revealed) {}
