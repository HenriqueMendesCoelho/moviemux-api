package com.moviemux.wishlist.adapter.controller.dto;

import com.moviemux.wishlist.domain.model.Wishlist;
import com.moviemux.wishlist.domain.model.WishlistOwner;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class WishlistResponseDto {

  private UUID id;
  private String name;
  private List<MovieWishlistResponseDto> moviesWishlists;
  private boolean shareable;
  private OffsetDateTime createdAt;
  private OffsetDateTime updatedAt;
  private WishlistUserResponseDto user;

  public WishlistResponseDto(Wishlist wishlist) {
    com.moviemux.wishlist.adapter.controller.mapper.WishlistHttpMapper.populate(this, wishlist);
  }

  @Data
  @AllArgsConstructor
  @NoArgsConstructor
  public static class WishlistUserResponseDto {
    public UUID id;
    public String name;

    public WishlistUserResponseDto(WishlistOwner user) {
      id = user.getId();
      name = user.getName();
    }
  }
}
