package com.moviemux.wishlist.domain.command;

import java.util.UUID;

public record AddWishlistMovieCommand(UUID wishlistId, UUID actorId, Long tmdbId) {}
