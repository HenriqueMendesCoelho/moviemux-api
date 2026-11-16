package com.moviemux.composition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.moviemux.wishlist.adapter.controller.WishlistQueryOperations;
import com.moviemux.wishlist.adapter.controller.WishlistUpdateOperations;
import com.moviemux.wishlist.adapter.controller.impl.WishlistControllerImpl;
import com.moviemux.wishlist.application.spring.controller.SpringUserWishlistController;
import com.moviemux.wishlist.domain.usecase.CreateUserWishlistUseCase;
import com.moviemux.wishlist.domain.usecase.DeleteUserWishlistUseCase;
import com.moviemux.wishlist.domain.usecase.SearchMoviesAlreadyRated;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;

class WishlistConstructorWiringTest {
  @Test
  void springWiresBothControllerLayersThroughTheirConstructors() {
    SearchMoviesAlreadyRated rated = mock(SearchMoviesAlreadyRated.class);
    UUID wishlistId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    when(rated.execute(wishlistId)).thenReturn(List.of());

    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean(WishlistQueryOperations.class, () -> mock(WishlistQueryOperations.class));
      context.registerBean(CreateUserWishlistUseCase.class, () -> mock(CreateUserWishlistUseCase.class));
      context.registerBean(WishlistUpdateOperations.class, () -> mock(WishlistUpdateOperations.class));
      context.registerBean(DeleteUserWishlistUseCase.class, () -> mock(DeleteUserWishlistUseCase.class));
      context.registerBean(SearchMoviesAlreadyRated.class, () -> rated);
      context.register(WishlistControllerImpl.class, SpringUserWishlistController.class);
      context.refresh();

      var response = context.getBean(SpringUserWishlistController.class).findById(wishlistId);

      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
      verify(rated).execute(wishlistId);
    }
  }
}
