package com.moviemux.movie.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Detached content for the socket contract; never a persistence entity. */
public record NotificationNote(Integer note, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                               UUID userId, String userName) { }
