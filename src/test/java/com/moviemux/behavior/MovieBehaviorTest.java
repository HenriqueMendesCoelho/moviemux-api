package com.moviemux.behavior;

import com.moviemux.movie.domain.command.*;
import java.time.Clock;
import com.moviemux.movie.domain.repository.*;

import com.moviemux.movie.domain.model.*;
import com.moviemux.movie.domain.exception.*;
import com.moviemux.movie.domain.usecase.impl.*;

import com.moviemux.user.domain.Role;
import com.moviemux.user.domain.exception.UserNotAuthorizedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Baseline characterization. Tests named legacy diagnose defects pending an explicitly reviewed fix. */
@ExtendWith(MockitoExtension.class)
class MovieBehaviorTest {
    @Mock MovieRepository repository;
    @Mock MovieIdentityRepository userRepository;
    @Mock MovieNoteRepository notes;
    @Mock MovieAnnouncement announcements;
    @Mock MovieNotificationRepository socket;
    @InjectMocks CreateMovieUseCaseImpl create;
    @InjectMocks DeleteMovieUseCaseImpl delete;
    @InjectMocks SearchMovieUseCaseImpl search;
    GetMovieUseCaseImpl get;
    @org.junit.jupiter.api.BeforeEach void initializeClock() { get=new GetMovieUseCaseImpl(repository,Clock.systemDefaultZone()); }

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID movieId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private MovieActor user(UUID id, String name) { return new MovieActor(id,name,name+"@example.test",Set.of(Role.USER)); }
    private Movie movie() { return Movie.builder().id(movieId).tmdbId(42L).user(user(userId,"Alice"))
            .notes(new ArrayList<>()).createdAt(OffsetDateTime.now().minusMinutes(5)).build(); }
    private MovieNote note(Movie movie, UUID id, String name, Integer score) {
        return MovieNote.builder().movie(movie).user(user(id,name)).note(score).build();
    }
    private CreateMovieNoteUseCaseImpl createNote() {
        return new CreateMovieNoteUseCaseImpl(notes,repository,userRepository,announcements,socket);
    }

    @Test void duplicateTmdbIdPreventsWritesAndNotifications() {
        Movie m=movie(); when(repository.findByTmdbId(42L)).thenReturn(m);
        assertThatThrownBy(() -> create.execute(new CreateMovieCommand(m,userId))).isInstanceOf(DuplicatedMovieException.class);
        verify(repository,never()).save(any());
        verifyNoInteractions(userRepository,announcements,socket);
    }

    @Test void createPersistsHiddenMovieBeforeBestEffortNotifications() throws Exception {
        Movie m=movie(); m.setShowNotes(true); MovieActor u=m.getUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));
        when(repository.save(m)).thenReturn(m);
        assertThat(create.execute(new CreateMovieCommand(m,userId))).isSameAs(m);
        assertThat(m.isShowNotes()).isFalse(); assertThat(m.getUser()).isSameAs(u);
        InOrder order=inOrder(userRepository,repository,announcements,socket);
        order.verify(repository).findByTmdbId(42L); order.verify(userRepository).findById(userId);
        order.verify(userRepository).flush(u.id()); order.verify(repository).save(m);
        order.verify(announcements).created(movieId); order.verify(socket).notifyAllMovies(null);
    }

    @Test void discordFailureStillAllowsSocketNotificationAndSuccessfulCreate() throws Exception {
        Movie m=movie(); when(userRepository.findById(userId)).thenReturn(Optional.of(m.getUser()));
        when(repository.save(m)).thenReturn(m);
        doThrow(new IllegalStateException("stub unavailable")).when(announcements).created(movieId);
        assertThat(create.execute(new CreateMovieCommand(m,userId))).isSameAs(m);
        verify(socket).notifyAllMovies(null);
    }

    @Test void persistenceFailurePreventsExternalNotifications() {
        Movie m=movie(); when(userRepository.findById(userId)).thenReturn(Optional.of(m.getUser()));
        when(repository.save(m)).thenThrow(new IllegalStateException("stub write failed"));
        assertThatThrownBy(() -> create.execute(new CreateMovieCommand(m,userId))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(announcements,socket);
    }

    @Test void deletingMissingMovieIsNoOp() throws Exception {
        delete.execute(new DeleteMovieCommand(movieId,userId)); verifyNoInteractions(userRepository,socket);
        verify(repository,never()).delete(any(Movie.class));
    }

    @Test void ordinaryUserCannotDeleteOrphanMovie() {
        Movie m=movie(); m.setUser(null);
        when(repository.findById(movieId)).thenReturn(Optional.of(m));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId,"Alice")));
        assertThatThrownBy(() -> delete.execute(new DeleteMovieCommand(movieId,userId))).isInstanceOf(UserNotAuthorizedException.class);
        verify(repository,never()).delete(any(Movie.class)); verifyNoInteractions(socket);
    }

    @Test void administratorCanDeleteOrphanMovieAndEventsCarryActor() throws Exception {
        Movie m=movie(); m.setUser(null); MovieActor u=user(userId,"Alice");u=new MovieActor(u.id(),u.name(),u.email(),Set.of(Role.USER,Role.ADM));
        when(repository.findById(movieId)).thenReturn(Optional.of(m));
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));
        delete.execute(new DeleteMovieCommand(movieId,userId));
        InOrder order=inOrder(repository,socket); order.verify(repository).findById(movieId);
        order.verify(repository).delete(m); order.verify(socket).notifyAllMovies(null);
        order.verify(socket).notifyMovie(movieId,"deleted-movie",null,userId.toString());
    }

    @Test void legacyDeleteRejectsOwnerWithDistinctEqualUuid() {
        Movie m=movie(); MovieActor detached=user(UUID.fromString(userId.toString()),"Alice");
        when(repository.findById(movieId)).thenReturn(Optional.of(m));
        when(userRepository.findById(userId)).thenReturn(Optional.of(detached));
        assertThatThrownBy(() -> delete.execute(new DeleteMovieCommand(movieId,userId))).isInstanceOf(UserNotAuthorizedException.class);
        verify(repository,never()).delete(any(Movie.class));
    }

    @Test void missingMovieReadUsesMovieNoteNotFoundException() {
        assertThatThrownBy(() -> get.execute(movieId,userId)).isInstanceOf(MovieNoteNotFoundException.class);
    }

    @Test void recentHiddenMovieSortsAlphabeticallyAndOnlyRevealsOwnScore() throws Exception {
        Movie m=movie(); MovieNote own=note(m,userId,"Zoe",8);
        MovieNote other=note(m,UUID.fromString("00000000-0000-0000-0000-000000000003"),"Alice",10);
        m.setNotes(new ArrayList<>(List.of(own,other))); when(repository.findById(movieId)).thenReturn(Optional.of(m));
        Movie result=get.execute(movieId,userId);
        assertThat(result.getNotes()).extracting(n->n.getUser().name()).containsExactly("Alice","Zoe");
        assertThat(own.getNote()).isEqualTo(8); assertThat(other.getNote()).isEqualTo(10);
        assertThat(result.getNotes().getFirst().getNote()).isNull();
        verify(repository,never()).save(any());
    }

    @Test void oldHiddenMovieIsPersistentlyRevealedAndSortedByScore() throws Exception {
        Movie m=movie();m.setCreatedAt(OffsetDateTime.now().minusHours(1));
        MovieNote lower=note(m,userId,"Alice",7);MovieNote higher=note(m,UUID.fromString("00000000-0000-0000-0000-000000000099"),"Zoe",9);
        m.setNotes(new ArrayList<>(List.of(lower,higher))); when(repository.findById(movieId)).thenReturn(Optional.of(m));
        Movie result=get.execute(movieId,userId);
        assertThat(result.isShowNotes()).isTrue();assertThat(result.getNotes()).extracting(MovieNote::getNote).containsExactly(9,7);
        assertThat(higher.getNote()).isEqualTo(9);verify(repository).save(m);
    }

    @Test void listParsesFiltersPreservesPageMetadataAndDoesNotRevealOldHiddenMovie() {
        Movie m=movie();m.setCreatedAt(OffsetDateTime.now().minusHours(1));
        m.setNotes(new ArrayList<>(List.of(note(m,UUID.fromString("00000000-0000-0000-0000-000000000099"),"Other",9))));
        MovieQuery query=new MovieQuery("The Movie","2,3","genre",1,2,false,List.of());
        when(repository.findFiltered(List.of("The","Movie"),List.of(2L,3L),query))
                .thenReturn(new MoviePage(List.of(m),5,query));
        MoviePage result=search.execute(query,userId);
        assertThat(result.totalElements()).isEqualTo(5);assertThat(result.query().page()).isEqualTo(1);
        assertThat(result.content().getFirst().isShowNotes()).isFalse();
        assertThat(result.content().getFirst().getNotes().getFirst().getNote()).isNull();
        verify(repository,never()).save(any());
    }

    @Test void malformedGenreFailsBeforeQueryingRepository() {
        assertThatThrownBy(() -> search.execute(new MovieQuery(null,"invalid",null,0,10,false,List.of()),userId))
                .isInstanceOf(NumberFormatException.class);verifyNoInteractions(repository);
    }

    @Test void missingMoviePreventsRatingWritesOrEffects() {
        when(userRepository.findByEmail("Alice@example.test")).thenReturn(user(userId,"Alice"));
        assertThatThrownBy(() -> createNote().execute(new CreateMovieNoteCommand(movieId,8,"Alice@example.test")))
                .isInstanceOf(MovieNotFoundException.class);
        verifyNoInteractions(notes,announcements,socket);
    }

    @Test void duplicateRatingPreventsWritesOrEffects() {
        Movie m=movie();MovieNote n=note(m,userId,"Alice",7);
        when(userRepository.findByEmail("Alice@example.test")).thenReturn(n.getUser());
        when(repository.findById(movieId)).thenReturn(Optional.of(m));
        when(notes.findById(new MovieNoteKey(userId,movieId))).thenReturn(Optional.of(n));
        assertThatThrownBy(() -> createNote().execute(new CreateMovieNoteCommand(movieId,8,"Alice@example.test")))
                .isInstanceOf(DuplicatedMovieNoteException.class);
        verify(notes,never()).save(any());verifyNoInteractions(announcements,socket);
    }

    @Test void hiddenRatingEventMasksCloneWithoutErasingSavedScore() throws Exception {
        Movie m=movie();MovieActor u=m.getUser();
        when(userRepository.findByEmail(u.getEmail())).thenReturn(u);
        when(repository.findById(movieId)).thenReturn(Optional.of(m));
        when(notes.save(any())).thenAnswer(i -> i.getArgument(0));
        MovieNote saved=createNote().execute(new CreateMovieNoteCommand(movieId,8,u.getEmail()));
        ArgumentCaptor<NotificationNote> payload=ArgumentCaptor.forClass(NotificationNote.class);
        verify(socket).notifyMovie(eq(movieId),eq("create-note"),payload.capture(),isNull());
        assertThat(saved.getNote()).isEqualTo(8); assertThat(payload.getValue()).isNotSameAs(saved);
        assertThat(payload.getValue().note()).isNull();assertThat(payload.getValue().userId()).isEqualTo(saved.getId().getUserId());
    }

    @Test void legacyNullRatingIsAcceptedByUseCase() throws Exception {
        Movie m=movie();MovieActor u=m.getUser();
        when(userRepository.findByEmail(u.getEmail())).thenReturn(u);
        when(repository.findById(movieId)).thenReturn(Optional.of(m));
        when(notes.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(createNote().execute(new CreateMovieNoteCommand(movieId,null,u.getEmail())).getNote()).isNull();
        verify(notes).save(any());
    }
}
