package com.moviemux.rankingcontent.domain.model;
import java.time.Instant;
import java.util.UUID;
/** Historical metadata only: never contains votes, authors or aggregates. */
public record RankingMovieRoundListItem(UUID roundId, long number, String description,
    Instant createdAt, Instant updatedAt, Boolean revealed) {}
