package com.moviemux.behavior;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.adapter.repository.postgres.entity.UserEntity;
import com.moviemux.user.domain.usecase.impl.CreateInviteUseCaseImpl;
import com.moviemux.user.domain.usecase.impl.DeleteInviteUseCaseImpl;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserLifecycleBehaviorTest {
  @Mock UserRepository repository;
  @Mock com.moviemux.movie.domain.repository.MovieOwnershipRemoval movieRemoval;
  @Mock com.moviemux.wishlist.domain.repository.WishlistOwnershipRemoval wishlistRemoval;
  @Mock com.moviemux.user.domain.repository.InviteRepository invites;

  @Spy
  com.moviemux.user.adapter.security.LegacyCodeGenerator codes =
      new com.moviemux.user.adapter.security.LegacyCodeGenerator();

  com.moviemux.application.spring.bridge.AccountRemovalBridge deleteUser;

  @org.junit.jupiter.api.BeforeEach
  void assembleRemovalWithPublicPorts() {
    deleteUser =
        new com.moviemux.application.spring.bridge.AccountRemovalBridge(
            new com.moviemux.user.adapter.repository.postgres.UserRemovalPreparationAdapter(
                repository),
            movieRemoval,
            wishlistRemoval);
  }

  @InjectMocks CreateInviteUseCaseImpl createInvite;
  @InjectMocks DeleteInviteUseCaseImpl deleteInvite;

  @Test
  void deletingAccountDetachesOwnedMoviesBeforeDeletingUser() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UserEntity user = UserEntity.builder().id(id).build();
    when(repository.getReferenceById(id)).thenReturn(user);
    deleteUser.remove(id);
    InOrder order = inOrder(repository, movieRemoval, wishlistRemoval);
    order.verify(repository).getReferenceById(id);
    order.verify(movieRemoval).detach(id);
    order.verify(wishlistRemoval).removeOwnedBy(id);
    order.verify(repository).delete(user);
  }

  @Test
  void movieDetachmentFailureStopsAccountDeletion() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UserEntity user = UserEntity.builder().id(id).build();
    when(repository.getReferenceById(id)).thenReturn(user);
    doThrow(new IllegalStateException("stub write failed")).when(movieRemoval).detach(id);
    assertThatThrownBy(() -> deleteUser.remove(id)).isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(wishlistRemoval);
    verify(repository, never()).delete(any(UserEntity.class));
  }

  @Test
  void invitationCollisionRetriesAndSavesOnlyAnUnusedCode() {
    when(invites.findByCode(anyString()))
        .thenReturn(new com.moviemux.user.domain.model.Invite(1L, "existing"))
        .thenReturn(null);
    when(invites.save(any())).thenAnswer(i -> i.getArgument(0));
    com.moviemux.user.domain.model.Invite created = createInvite.execute();
    assertThat(created.getCode()).matches("KB-[0-9]{8,9}");
    verify(invites, times(2)).findByCode(anyString());
    verify(invites).save(created);
  }

  @Test
  void deletingUnknownInvitationDoesNotWrite() {
    deleteInvite.execute("missing");
    verify(invites, never()).delete(any());
  }

  @Test
  void deletingKnownInvitationDeletesExactlyTheMatchedRecord() {
    com.moviemux.user.domain.model.Invite invite =
        new com.moviemux.user.domain.model.Invite(1L, "known");
    when(invites.findByCode("known")).thenReturn(invite);
    deleteInvite.execute("known");
    verify(invites).delete(invite);
  }
}
