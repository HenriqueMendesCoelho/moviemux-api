package com.moviemux.wishlist.domain.command;

import java.util.UUID;

public record DeleteWishlistCommand(UUID wishlistId, UUID actorId) {}
