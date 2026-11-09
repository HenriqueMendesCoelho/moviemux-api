package com.moviemux.behavior;

import com.moviemux.movie.adapter.repository.MovieRepository;
import com.moviemux.movie.domain.Movie;
import com.moviemux.user.adapter.repository.*;
import com.moviemux.user.domain.*;
import com.moviemux.user.usecase.impl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserLifecycleBehaviorTest {
    @Mock UserRepository repository;
    @Mock MovieRepository movieRepository;
    @Mock InviteRepository invites;
    @InjectMocks DeleteUserUseCaseImpl deleteUser;
    @InjectMocks CreateInviteUseCaseImpl createInvite;
    @InjectMocks DeleteInviteUseCaseImpl deleteInvite;

    @Test void deletingAccountDetachesOwnedMoviesBeforeDeletingUser() {
        UUID id=UUID.fromString("00000000-0000-0000-0000-000000000001");
        User user=User.builder().id(id).build();
        Movie movie=Movie.builder().id(UUID.fromString("00000000-0000-0000-0000-000000000002")).user(user).build();
        user.setMovies(List.of(movie));
        when(repository.getReferenceById(id)).thenReturn(user);
        deleteUser.deleteUser(id);
        assertThat(movie.getUser()).isNull();assertThat(user.getMovies()).isNull();
        InOrder order=inOrder(repository,movieRepository);
        order.verify(repository).getReferenceById(id);order.verify(movieRepository).saveAllAndFlush(List.of(movie));
        order.verify(repository).save(user);order.verify(repository).delete(user);
    }

    @Test void movieDetachmentFailureStopsAccountDeletion() {
        UUID id=UUID.fromString("00000000-0000-0000-0000-000000000001");
        User user=User.builder().id(id).build();user.setMovies(List.of());
        when(repository.getReferenceById(id)).thenReturn(user);
        when(movieRepository.saveAllAndFlush(any())).thenThrow(new IllegalStateException("stub write failed"));
        assertThatThrownBy(() -> deleteUser.deleteUser(id)).isInstanceOf(IllegalStateException.class);
        verify(repository,never()).save(any());verify(repository,never()).delete(any(User.class));
    }

    @Test void invitationCollisionRetriesAndSavesOnlyAnUnusedCode() {
        when(invites.findByCode(anyString())).thenReturn(new Invite(1L,"existing")).thenReturn(null);
        when(invites.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        Invite created=createInvite.create();
        assertThat(created.getCode()).matches("KB-[0-9]{8,9}");
        verify(invites,times(2)).findByCode(anyString());verify(invites).saveAndFlush(created);
    }

    @Test void deletingUnknownInvitationDoesNotWrite() {
        deleteInvite.delete("missing");verify(invites,never()).delete(any());
    }

    @Test void deletingKnownInvitationDeletesExactlyTheMatchedRecord() {
        Invite invite=new Invite(1L,"known");when(invites.findByCode("known")).thenReturn(invite);
        deleteInvite.delete("known");verify(invites).delete(invite);
    }
}
