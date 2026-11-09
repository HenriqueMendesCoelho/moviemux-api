package com.moviemux.qualitycoverage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity;
import com.moviemux.movie.adapter.repository.postgres.mapper.MovieEntityMapper;
import com.moviemux.user.domain.model.Role;
import com.moviemux.user.domain.model.UserIdentity;
import com.moviemux.user.domain.repository.UserIdentityLookup;
import com.moviemux.wishlist.adapter.repository.postgres.entity.WishlistEntity;
import com.moviemux.wishlist.adapter.repository.postgres.mapper.WishlistEntityMapper;
import com.moviemux.wishlist.domain.model.Wishlist;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScalarIdentityMapperTest {
  @Test
  void nullMovieAndAbsentActorNeverTriggerAnIdentityLookup() {
    var identities = mock(UserIdentityLookup.class);
    assertThat(MovieEntityMapper.movie(null, identities)).isNull();
    assertThat(MovieEntityMapper.actor(null, identities)).isNull();
    var id = UUID.randomUUID();
    var movie = MovieEntityMapper.shallow(MovieEntity.builder().id(id).build(), identities);
    assertThat(movie.getId()).isEqualTo(id);
    assertThat(movie.getUser()).isNull();
    verifyNoInteractions(identities);
  }

  @Test
  void missingPublicIdentityDoesNotInventActorOrLoseMovieIdentity() {
    var identities = mock(UserIdentityLookup.class);
    var owner = UUID.randomUUID();
    var id = UUID.randomUUID();
    when(identities.find(owner)).thenReturn(Optional.empty());
    var movie =
        MovieEntityMapper.movie(MovieEntity.builder().id(id).userId(owner).build(), identities);
    assertThat(movie.getId()).isEqualTo(id);
    assertThat(movie.getUser()).isNull();
    verify(identities).find(owner);
    verifyNoMoreInteractions(identities);
  }

  @Test
  void wishlistOwnerResolvesThroughPublicIdentityAndRoundTripsScalarForeignKey() {
    var identities = mock(UserIdentityLookup.class);
    var owner = UUID.randomUUID();
    var id = UUID.randomUUID();
    when(identities.find(owner))
        .thenReturn(
            Optional.of(new UserIdentity(owner, "Ada", "ada@example.test", Set.of(Role.USER))));
    var mapper = new WishlistEntityMapper(identities);
    var source =
        WishlistEntity.builder().id(id).name("Favorites").userId(owner).shareable(true).build();
    Wishlist model = mapper.toDomain(source);
    assertThat(model.getUser().id()).isEqualTo(owner);
    assertThat(model.getUser().name()).isEqualTo("Ada");
    var rebuilt = mapper.toEntity(model);
    assertThat(rebuilt.getUserId()).isEqualTo(owner);
    assertThat(rebuilt.getId()).isEqualTo(id);
    assertThat(rebuilt.getName()).isEqualTo("Favorites");
    assertThat(rebuilt.isShareable()).isTrue();
    verify(identities).find(owner);
    verifyNoMoreInteractions(identities);
  }

  @Test
  void wishlistWithoutOwnerOrWithMissingIdentityKeepsItsOwnData() {
    var identities = mock(UserIdentityLookup.class);
    var mapper = new WishlistEntityMapper(identities);
    var withoutOwner = mapper.toDomain(WishlistEntity.builder().name("Unowned").build());
    assertThat(withoutOwner.getName()).isEqualTo("Unowned");
    assertThat(withoutOwner.getUser()).isNull();
    assertThat(mapper.toEntity(withoutOwner).getUserId()).isNull();
    verifyNoInteractions(identities);
    var owner = UUID.randomUUID();
    when(identities.find(owner)).thenReturn(Optional.empty());
    var unavailableOwner =
        mapper.toDomain(WishlistEntity.builder().name("Historical").userId(owner).build());
    assertThat(unavailableOwner.getName()).isEqualTo("Historical");
    assertThat(unavailableOwner.getUser()).isNull();
    verify(identities).find(owner);
    verifyNoMoreInteractions(identities);
  }
}
