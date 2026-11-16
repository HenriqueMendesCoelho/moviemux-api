package com.moviemux.wishlist.domain.command;

import com.moviemux.wishlist.domain.model.Wishlist;
import java.util.UUID;

public record UpdateWishlistCommand(Wishlist wishlist, UUID actorId) {}
