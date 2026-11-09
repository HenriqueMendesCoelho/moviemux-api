package com.moviemux.behavior;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.domain.Movie;
import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.domain.User;
import com.moviemux.user.domain.usecase.impl.CreateInviteUseCaseImpl;
import com.moviemux.user.domain.usecase.impl.DeleteInviteUseCaseImpl;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserLifecycleBehaviorTest {
  @Mock UserRepository repository;
  @Mock MovieRepository movieRepository;
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
            new com.moviemux.movie.adapter.repository.postgres.MovieOwnershipRemovalAdapter(
                movieRepository));
  }

  @InjectMocks CreateInviteUseCaseImpl createInvite;
  @InjectMocks DeleteInviteUseCaseImpl deleteInvite;

  @Test
  void deletingAccountDetachesOwnedMoviesBeforeDeletingUser() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    User user = User.builder().id(id).build();
    Movie movie =
        Movie.builder()
            .id(UUID.fromString("00000000-0000-0000-0000-000000000002"))
            .user(user)
            .build();
    user.setMovies(List.of(movie));
    when(movieRepository.getReferenceById(movie.getId())).thenReturn(movie);
    when(repository.getReferenceById(id)).thenReturn(user);
    deleteUser.remove(id);
    assertThat(movie.getUser()).isNull();
    assertThat(user.getMovies()).isNull();
    InOrder order = inOrder(repository, movieRepository);
    order.verify(repository).getReferenceById(id);
    order.verify(movieRepository).saveAllAndFlush(List.of(movie));
    order.verify(repository).save(user);
    order.verify(repository).delete(user);
  }

  @Test
  void movieDetachmentFailureStopsAccountDeletion() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    User user = User.builder().id(id).build();
    user.setMovies(List.of());
    when(repository.getReferenceById(id)).thenReturn(user);
    when(movieRepository.saveAllAndFlush(any()))
        .thenThrow(new IllegalStateException("stub write failed"));
    assertThatThrownBy(() -> deleteUser.remove(id)).isInstanceOf(IllegalStateException.class);
    verify(repository, never()).save(any());
    verify(repository, never()).delete(any(User.class));
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
