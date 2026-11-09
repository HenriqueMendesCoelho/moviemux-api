package com.moviemux.wishlist.domain.command;

import java.util.UUID;

public record CreateWishlistCommand(String name, UUID actorId) {}
