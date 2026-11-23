package com.moviemux.ranking.domain.model;

/** Explicit per-owner cap for all owned collections, including archived, with no numerical default. */
public record RankingCreationQuota(int maxOwned) {}
