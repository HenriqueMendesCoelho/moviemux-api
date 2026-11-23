package com.moviemux.ranking.domain.model;

/** Explicit deployment policy, not a product default. */
public record RankingCreationQuota(int maxOwned, boolean includeArchived) {}
