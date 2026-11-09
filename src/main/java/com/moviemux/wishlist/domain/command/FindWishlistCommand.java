package com.moviemux.wishlist.domain.command;

import java.util.UUID;

public record FindWishlistCommand(UUID wishlistId, UUID actorId) {}
