package com.moviemux.ranking.domain.model;

/** Private contextual summary with explicit proof of current owner-only OPEN sharing eligibility. */
public record RankingContextSummary(RankingSummary summary, boolean canShareOpenLink) {}
