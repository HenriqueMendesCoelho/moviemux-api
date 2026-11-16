package com.moviemux.wishlist.adapter.controller.dto;

import com.moviemux.wishlist.domain.model.Wishlist;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.validator.constraints.Length;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class WishlistRequestDto {

  private UUID id;

  @NotBlank
  @Length(min = 3, max = 30)
  private String name;

  private List<MovieWishlistRequestDto> moviesWishlists;
  private boolean shareable;

  public Wishlist toDomain() {
    return com.moviemux.wishlist.adapter.controller.mapper.WishlistHttpMapper.toDomain(this);
  }
}
