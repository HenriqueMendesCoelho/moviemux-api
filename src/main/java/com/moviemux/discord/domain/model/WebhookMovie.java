package com.moviemux.discord.domain.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record WebhookMovie(UUID id, String title, String imageUrl, boolean showNotes,
                           String registeredBy, OffsetDateTime createdAt, String messageId, List<Rating> ratings) {
    public WebhookMovie { ratings = ratings == null ? null : List.copyOf(ratings); }
    public record Rating(String userName, Integer note) { }
}
