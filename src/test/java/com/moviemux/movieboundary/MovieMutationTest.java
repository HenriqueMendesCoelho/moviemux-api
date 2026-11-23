package com.moviemux.movieboundary;

import com.moviemux.movie.domain.command.*;
import com.moviemux.movie.domain.exception.*;
import com.moviemux.movie.domain.model.*;
import com.moviemux.movie.domain.repository.*;
import com.moviemux.movie.domain.usecase.impl.*;
import com.moviemux.user.domain.model.Role;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import com.moviemux.kronusintegrationtool.domain.repository.KronusCatalogRepository;
import com.moviemux.kronusintegrationtool.domain.model.CatalogGenre;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MovieMutationTest {
    private final MovieRepository movies = mock(MovieRepository.class);
    private final MovieNoteRepository notes = mock(MovieNoteRepository.class);
    private final MovieIdentityRepository identities = mock(MovieIdentityRepository.class);
    private final MovieAnnouncement announcements = mock(MovieAnnouncement.class);
    private final MovieNotificationRepository notifications = mock(MovieNotificationRepository.class);
    private final UUID movieId = UUID.randomUUID(), userId = UUID.randomUUID();

    private MovieActor actor(boolean admin) {
        return new MovieActor(userId,"Ada","ada@example.test",admin?Set.of(Role.USER,Role.ADM):Set.of(Role.USER));
    }
    private Movie movie(boolean showNotes) {
        return Movie.builder().id(movieId).user(actor(false)).showNotes(showNotes).englishTitle("Legacy English").genres(List.of()).build();
    }
    private MovieNote note(boolean showNotes) {
        return MovieNote.builder().movie(movie(showNotes)).user(actor(false)).note(7).build();
    }

    @Test void canonicalConflictIsRedactedAndDoesNotAnnounceOrPublish() {
        var existing = movie(false);
        when(movies.findById(movieId)).thenReturn(Optional.of(existing));
        when(identities.findByEmail("ada@example.test")).thenReturn(actor(false));
        when(movies.save(existing)).thenThrow(new com.moviemux.movie.domain.exception.MovieWriteConflictException());
        assertThatThrownBy(() -> new UpdateMovieUseCaseImpl(movies, identities, announcements, notifications)
            .execute(new UpdateMovieCommand(movie(true), movieId, "ada@example.test")))
            .isInstanceOf(com.moviemux.movie.domain.exception.MovieWriteConflictException.class)
            .hasMessage("Movie duplicated").hasNoCause();
        verifyNoInteractions(announcements, notifications);
    }

    @Test void updateMissingMovieDoesNotWriteOrNotify() {
        when(identities.findByEmail("ada@example.test")).thenReturn(actor(false));
        var operation = new UpdateMovieUseCaseImpl(movies,identities,announcements,notifications);
        assertThatThrownBy(() -> operation.execute(new UpdateMovieCommand(movie(false),movieId,"ada@example.test")))
                .isInstanceOf(MovieNotFoundException.class);
        verify(movies,never()).save(any()); verifyNoInteractions(announcements,notifications);
    }

    @Test void ordinaryActorCannotUpdateOrphanMovie() {
        Movie existing=movie(false);existing.setUser(null);
        when(movies.findById(movieId)).thenReturn(Optional.of(existing));
        when(identities.findByEmail("ada@example.test")).thenReturn(actor(false));
        assertThatThrownBy(() -> new UpdateMovieUseCaseImpl(movies,identities,announcements,notifications)
                .execute(new UpdateMovieCommand(movie(true),movieId,"ada@example.test")))
                .isInstanceOf(UserNotAuthorizedException.class);
        verify(movies,never()).save(any()); verifyNoInteractions(announcements,notifications);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void equalButDistinctOwnerUuidCanMutate(boolean update) throws Exception {
        Movie existing = movie(false);
        UUID distinct = UUID.fromString(userId.toString());
        assertThat(distinct).isEqualTo(userId).isNotSameAs(userId);
        MovieActor current = new MovieActor(distinct, "Ada", "ada@example.test", Set.of(Role.USER));
        stubMutationIdentity(existing, current, update);
        executeMutation(existing, current, update);
        if (update) verify(movies).save(existing);
        else verify(movies).delete(existing);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void differentOrdinaryUserCannotMutate(boolean update) {
        Movie existing = movie(false);
        UUID otherId = new UUID(userId.getMostSignificantBits(), userId.getLeastSignificantBits() ^ 1L);
        MovieActor current = new MovieActor(otherId, "Other", "other@example.test", Set.of(Role.USER));
        stubMutationIdentity(existing, current, update);
        assertThatThrownBy(() -> executeMutation(existing, current, update))
            .isInstanceOf(UserNotAuthorizedException.class);
        verify(movies, never()).save(any());
        verify(movies, never()).delete(any());
        verifyNoInteractions(announcements, notifications);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void administratorCanMutateAnotherCreatorsMovie(boolean update) throws Exception {
        Movie existing = movie(false);
        UUID adminId = new UUID(userId.getMostSignificantBits(), userId.getLeastSignificantBits() ^ 1L);
        MovieActor current = new MovieActor(adminId, "Admin", "admin@example.test", Set.of(Role.USER, Role.ADM));
        stubMutationIdentity(existing, current, update);
        executeMutation(existing, current, update);
        if (update) verify(movies).save(existing);
        else verify(movies).delete(existing);
    }

    private void stubMutationIdentity(Movie existing, MovieActor current, boolean update) {
        when(movies.findById(movieId)).thenReturn(Optional.of(existing));
        if (update) when(identities.findByEmail(current.email())).thenReturn(current);
        else when(identities.findById(current.id())).thenReturn(Optional.of(current));
    }

    private void executeMutation(Movie existing, MovieActor current, boolean update) throws Exception {
        if (update) {
            when(movies.save(existing)).thenReturn(existing);
            new UpdateMovieUseCaseImpl(movies, identities, announcements, notifications)
                .execute(new UpdateMovieCommand(movie(false), movieId, current.email()));
        } else {
            new DeleteMovieUseCaseImpl(movies, identities, notifications)
                .execute(new DeleteMovieCommand(movieId, current.id()));
        }
    }

    @Test void administratorUpdatePreservesLegacyFieldsAndEmitsOnlyVisibilityChange() throws Exception {
        Movie existing=movie(false);existing.setUser(null);
        Movie request=movie(true);request.setEnglishTitle("Ignored legacy field");request.setDescription("Updated");
        when(movies.findById(movieId)).thenReturn(Optional.of(existing));
        when(identities.findByEmail("ada@example.test")).thenReturn(actor(true));
        when(movies.save(existing)).thenReturn(existing);
        Movie result=new UpdateMovieUseCaseImpl(movies,identities,announcements,notifications)
                .execute(new UpdateMovieCommand(request,movieId,"ada@example.test"));
        assertThat(result.getEnglishTitle()).isEqualTo("Legacy English");
        assertThat(result.getDescription()).isEqualTo("Updated");
        assertThat(result.isShowNotes()).isTrue();
        var order=inOrder(movies,announcements,notifications);
        order.verify(movies).findById(movieId);order.verify(movies).save(existing);
        order.verify(announcements).updated(movieId);order.verify(notifications).notifyMovie(movieId,"update-movie",null,null);
    }

    @Test void unchangedVisibilityDoesNotSendSocketUpdate() throws Exception {
        Movie existing=movie(false);
        when(movies.findById(movieId)).thenReturn(Optional.of(existing));
        when(identities.findByEmail("ada@example.test")).thenReturn(actor(true));
        when(movies.save(existing)).thenReturn(existing);
        new UpdateMovieUseCaseImpl(movies,identities,announcements,notifications)
                .execute(new UpdateMovieCommand(movie(false),movieId,"ada@example.test"));
        verify(announcements).updated(movieId);verifyNoInteractions(notifications);
    }

    @Test void updateMissingRatingDoesNotWriteOrNotify() {
        assertThatThrownBy(() -> new UpdateMovieNoteUseCaseImpl(notes,announcements,notifications)
                .execute(new UpdateMovieNoteCommand(userId,movieId,8))).isInstanceOf(MovieNoteNotFoundException.class);
        verify(notes,never()).save(any()); verifyNoInteractions(announcements,notifications);
    }

    @Test void updateRatingMasksOnlyNotificationAndPreservesSavedValue() throws Exception {
        MovieNote existing=note(false);
        when(notes.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(notes.save(existing)).thenReturn(existing);
        assertThat(new UpdateMovieNoteUseCaseImpl(notes,announcements,notifications)
                .execute(new UpdateMovieNoteCommand(userId,movieId,10)).getNote()).isEqualTo(10);
        var payload=org.mockito.ArgumentCaptor.forClass(NotificationNote.class);
        verify(notifications).notifyMovie(eq(movieId),eq("update-note"),payload.capture(),isNull());
        assertThat(payload.getValue().note()).isNull();assertThat(existing.getNote()).isEqualTo(10);
        assertThat(payload.getValue().userId()).isEqualTo(userId);
    }

    @Test void visibleRatingNotificationIncludesScoreAndSocketFailureRemainsBestEffort() throws Exception {
        MovieNote existing=note(true);
        when(notes.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(notes.save(existing)).thenReturn(existing);
        doThrow(new IllegalStateException("synthetic socket error")).when(notifications).notifyMovie(any(),any(),any(),isNull());
        assertThat(new UpdateMovieNoteUseCaseImpl(notes,announcements,notifications)
                .execute(new UpdateMovieNoteCommand(userId,movieId,0)).getNote()).isZero();
        verify(notifications).notifyMovie(movieId,"update-note",new NotificationNote(0,null,null,userId,"Ada"),null);
    }

    @Test void deletingMissingRatingIsNoOp() {
        new DeleteMovieNoteUseCaseImpl(notes,announcements,notifications).execute(new DeleteMovieNoteCommand(userId,movieId));
        verify(notes,never()).delete(any());verifyNoInteractions(announcements,notifications);
    }

    @Test void deletionSendsActorSnapshotAfterPersistence() {
        MovieNote existing=note(false);
        when(notes.findById(existing.getId())).thenReturn(Optional.of(existing));
        new DeleteMovieNoteUseCaseImpl(notes,announcements,notifications).execute(new DeleteMovieNoteCommand(userId,movieId));
        var order=inOrder(notes,announcements,notifications);
        order.verify(notes).findById(existing.getId());order.verify(notes).delete(existing);
        order.verify(announcements).updated(movieId);
        order.verify(notifications).notifyMovie(movieId,"delete-note",new NotificationNote(null,null,null,userId,null),null);
    }

    @Test void genreSynchronizationSkipsExistingNamesAndPreservesCatalogIdentifiers() {
        var catalog=mock(KronusCatalogRepository.class);var genres=mock(MovieGenreRepository.class);
        when(catalog.listGenres()).thenReturn(List.of(new CatalogGenre(18L,"Drama"),new CatalogGenre(35L,"Comedy")));
        when(genres.findByName("Drama")).thenReturn(MovieGenre.builder().id(1L).name("Drama").build());
        new CreateMovieGenreUseCaseImpl(catalog,genres).execute();
        var saved=org.mockito.ArgumentCaptor.forClass(MovieGenre.class);verify(genres).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(35L);
        assertThat(saved.getValue().getTmdbId()).isEqualTo(35L);
        assertThat(saved.getValue().getName()).isEqualTo("Comedy");
    }
}
