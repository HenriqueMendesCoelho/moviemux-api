package com.moviemux.behavior;

import com.moviemux.kronusintegrationtool.adapter.repository.rest.KronusIntegrationToolRepository;
import com.moviemux.user.adapter.repository.*;
import com.moviemux.user.domain.*;
import com.moviemux.user.usecase.exception.*;
import com.moviemux.user.usecase.impl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Baseline characterization. Tests named legacy diagnose defects pending an explicitly reviewed fix. */
@ExtendWith(MockitoExtension.class)
class UserBehaviorTest {
    @Mock UserRepository repository;
    @Mock InviteRepository inviteRepository;
    @Mock BCryptPasswordEncoder passwordEncoder;
    @Mock KronusIntegrationToolRepository kronusIntegrationToolRepository;
    @InjectMocks CreateUserUseCaseImpl create;
    @InjectMocks SearchUserUseCaseImpl search;
    @InjectMocks UpdateUserUseCaseImpl update;
    @InjectMocks UserRedefinePasswordUseCaseImpl reset;

    private User user() {
        User u = User.builder().id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .name("Alice").email("alice@example.test").password("plain").build();
        u.setPreferences(Preferences.builder().user(u).notify(true).build());
        u.setStatistics(Statistics.builder().user(u).build());
        return u;
    }

    @Test void firstUserGetsAdminAndInitializedProfileBeforeWelcomeMail() throws Exception {
        User u = user();
        when(passwordEncoder.encode("plain")).thenReturn("encoded");
        when(repository.saveAndFlush(u)).thenReturn(u);
        assertThat(create.save(u, null)).isSameAs(u);
        assertThat(u.getRoles()).containsExactlyInAnyOrder(Role.USER, Role.ADM);
        assertThat(u.getPassword()).isEqualTo("encoded");
        assertThat(u.getPreferences().isNotify()).isTrue();
        assertThat(u.getStatistics().getConsecutiveFailedLoginAttempts()).isZero();
        InOrder order = inOrder(repository, kronusIntegrationToolRepository);
        order.verify(repository).count();
        order.verify(repository).findByEmail(u.getEmail());
        order.verify(repository).saveAndFlush(u);
        order.verify(kronusIntegrationToolRepository).sendMailTemplate(any());
        verifyNoInteractions(inviteRepository);
    }

    @Test void subsequentUserRequiresValidInviteWithoutWritingOrMailing() {
        when(repository.count()).thenReturn(1L);
        assertThatThrownBy(() -> create.save(user(), "missing")).isInstanceOf(InviteNotValidException.class);
        verify(repository, never()).saveAndFlush(any());
        verify(inviteRepository, never()).delete(any());
        verifyNoInteractions(passwordEncoder, kronusIntegrationToolRepository);
    }

    @Test void legacyRegistrationConsumesInviteBeforeDetectingDuplicate() {
        User u = user();
        Invite invite = new Invite(1L, "synthetic-invite");
        when(repository.count()).thenReturn(1L);
        when(inviteRepository.findByCode(invite.getCode())).thenReturn(invite);
        when(repository.findByEmail(u.getEmail())).thenReturn(u);
        assertThatThrownBy(() -> create.save(u, invite.getCode())).isInstanceOf(DuplicatedUserException.class);
        InOrder order = inOrder(inviteRepository, repository);
        order.verify(repository).count();
        order.verify(inviteRepository).findByCode(invite.getCode());
        order.verify(inviteRepository).delete(invite);
        order.verify(repository).findByEmail(u.getEmail());
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(kronusIntegrationToolRepository);
    }

    @Test void welcomeFailureDoesNotUndoSuccessfulRegistration() throws Exception {
        User u = user();
        when(repository.saveAndFlush(u)).thenReturn(u);
        doThrow(new IllegalStateException("stub unavailable")).when(kronusIntegrationToolRepository).sendMailTemplate(any());
        assertThat(create.save(u, null)).isSameAs(u);
        verify(repository).saveAndFlush(u);
    }

    @Test void ordinaryUserCannotReadAnotherProfile() {
        User u = user();
        when(repository.findByEmail(u.getEmail())).thenReturn(u);
        assertThatThrownBy(() -> search.getUserByEmail("other@example.test", u.getEmail()))
                .isInstanceOf(UserNotAuthorizedException.class);
    }

    @Test void ownerCanReadOwnProfile() throws Exception {
        User u = user();
        when(repository.findByEmail(u.getEmail())).thenReturn(u);
        assertThat(search.getUserByEmail(u.getEmail(), u.getEmail())).isSameAs(u);
    }

    @Test void adminGetsNotFoundForMissingProfile() {
        User u = user(); u.addRole(Role.ADM);
        when(repository.findByEmail(u.getEmail())).thenReturn(u);
        assertThatThrownBy(() -> search.getUserByEmail("missing@example.test", u.getEmail()))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test void emptyUserDirectoryIsNotFound() {
        when(repository.findAll()).thenReturn(List.of());
        assertThatThrownBy(search::getAllUsers).isInstanceOf(UserNotFoundException.class);
    }

    @Test void legacyProfileUpdateRejectsEqualButDistinctUuidInstances() {
        User u = user(); UUID detachedId = UUID.fromString(u.getId().toString());
        when(repository.findByEmail(u.getEmail())).thenReturn(u);
        when(repository.findById(detachedId)).thenReturn(Optional.of(u));
        assertThat(detachedId).isEqualTo(u.getId()).isNotSameAs(u.getId());
        assertThatThrownBy(() -> update.update(u, detachedId, u.getEmail()))
                .isInstanceOf(UserNotAuthorizedException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void incorrectCurrentPasswordPreventsWrite() {
        User u = user(); when(repository.findById(u.getId())).thenReturn(Optional.of(u));
        assertThatThrownBy(() -> update.updateUserPassoword(u.getId(), "wrong", "new"))
                .isInstanceOf(UserNotAuthorizedException.class);
        verify(passwordEncoder, never()).encode(any());
        verify(repository, never()).saveAndFlush(any());
        assertThat(u.getPassword()).isEqualTo("plain");
    }

    @Test void validPasswordIsEncodedBeforeSave() throws Exception {
        User u = user(); when(repository.findById(u.getId())).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("old", "plain")).thenReturn(true);
        when(passwordEncoder.encode("new")).thenReturn("new-hash");
        when(repository.saveAndFlush(u)).thenReturn(u);
        assertThat(update.updateUserPassoword(u.getId(), "old", "new").getPassword()).isEqualTo("new-hash");
    }

    @Test void promotingAnExistingAdminDoesNotWrite() throws Exception {
        User u = user(); u.addRole(Role.ADM);
        when(repository.findById(u.getId())).thenReturn(Optional.of(u));
        assertThat(update.promoteUserToAdmin(u.getId())).isSameAs(u);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void resetForUnknownEmailDoesNotSendOrWrite() {
        reset.createRedefinePassword("unknown@example.test");
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(kronusIntegrationToolRepository);
    }

    @Test void legacyResetSendsEmailBeforePersistingGeneratedKey() {
        User u = user(); when(repository.findByEmail(u.getEmail())).thenReturn(u);
        reset.createRedefinePassword(u.getEmail());
        assertThat(u.getRedefinePasswordKey()).matches("[a-zA-Z0-9]{200}");
        assertThat(u.getRedefinePasswordKeyCreatedAt()).isNotNull();
        InOrder order = inOrder(kronusIntegrationToolRepository, repository);
        order.verify(repository).findByEmail(u.getEmail());
        order.verify(kronusIntegrationToolRepository).sendMailTemplate(any());
        order.verify(repository).saveAndFlush(u);
    }

    @Test void failedResetEmailLeavesNoPersistedKey() {
        User u = user(); when(repository.findByEmail(u.getEmail())).thenReturn(u);
        doThrow(new IllegalStateException("stub unavailable")).when(kronusIntegrationToolRepository).sendMailTemplate(any());
        assertThatThrownBy(() -> reset.createRedefinePassword(u.getEmail())).isInstanceOf(IllegalStateException.class);
        assertThat(u.getRedefinePasswordKey()).isNull();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void missingResetKeyFailsWithoutWriting() {
        assertThatThrownBy(() -> reset.redefine("missing", "a@example.test", "new"))
                .isInstanceOf(UserRedefinePasswordKeyNotFound.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void resetRejectsEmailMismatchWithoutConsumingKey() {
        User u = user(); u.setRedefinePasswordKey("key");
        when(repository.findByRedefinePasswordKey("key")).thenReturn(u);
        assertThatThrownBy(() -> reset.redefine("key", "other@example.test", "new"))
                .isInstanceOf(UserNotAuthorizedException.class);
        assertThat(u.getRedefinePasswordKey()).isEqualTo("key");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test void expiredResetKeyIsClearedAndSavedBeforeRejection() {
        User u = user(); u.setRedefinePasswordKey("key");
        u.setRedefinePasswordKeyCreatedAt(OffsetDateTime.now().minusHours(2));
        when(repository.findByRedefinePasswordKey("key")).thenReturn(u);
        assertThatThrownBy(() -> reset.redefine("key", u.getEmail(), "new"))
                .isInstanceOf(UserRedefinePasswordKeyInvalid.class);
        assertThat(u.getRedefinePasswordKey()).isNull();
        assertThat(u.getRedefinePasswordKeyCreatedAt()).isNull();
        verify(repository).saveAndFlush(u); verifyNoInteractions(passwordEncoder);
    }

    @Test void validResetAcceptsCaseInsensitiveEmailAndConsumesKey() throws Exception {
        User u = user(); u.setRedefinePasswordKey("key");
        u.setRedefinePasswordKeyCreatedAt(OffsetDateTime.now().minusMinutes(5));
        when(repository.findByRedefinePasswordKey("key")).thenReturn(u);
        when(passwordEncoder.encode("new")).thenReturn("hash");
        when(repository.saveAndFlush(u)).thenReturn(u);
        assertThat(reset.redefine("key", "ALICE@example.test", "new")).isSameAs(u);
        assertThat(u.getPassword()).isEqualTo("hash");
        assertThat(u.getRedefinePasswordKey()).isNull();
        assertThat(u.getRedefinePasswordKeyCreatedAt()).isNull();
    }
}
