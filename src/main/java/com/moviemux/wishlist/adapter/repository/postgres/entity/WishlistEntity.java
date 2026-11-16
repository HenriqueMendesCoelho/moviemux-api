package com.moviemux.wishlist.adapter.repository.postgres.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

@Entity(name = "Wishlist")
@Table(
    name = "user_wishlist",
    uniqueConstraints = {@UniqueConstraint(columnNames = {"user_id", "name"})})
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WishlistEntity implements Serializable {

  private static final long serialVersionUID = 1763928844019419528L;

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  @Column
  private UUID id;

  @Column(length = 30)
  private String name;

  @Column(name = "user_id")
  private UUID userId;

  @OneToMany(mappedBy = "wishlist", cascade = CascadeType.ALL)
  @OrderBy("wishlistOrder asc")
  private List<MoviesWishlistsEntity> moviesWishlists;

  @Column(nullable = false)
  private boolean shareable;

  @CreationTimestamp
  @Column(nullable = false, columnDefinition = "timestamp with time zone")
  private OffsetDateTime createdAt;

  @Column(columnDefinition = "timestamp with time zone")
  private OffsetDateTime updatedAt;
}
