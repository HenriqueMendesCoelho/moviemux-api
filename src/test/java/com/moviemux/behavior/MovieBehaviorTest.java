package com.moviemux.behavior;

import com.moviemux.discord.usecase.*;
import com.moviemux.movie.adapter.repository.*;
import com.moviemux.movie.adapter.repository.rest.MovieSocketRespository;
import com.moviemux.movie.domain.*;
import com.moviemux.movie.usecase.exception.*;
import com.moviemux.movie.usecase.impl.*;
import com.moviemux.user.adapter.repository.UserRepository;
import com.moviemux.user.domain.*;
import com.moviemux.user.usecase.exception.UserNotAuthorizedException;
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
    @Mock UserRepository userRepository;
    @Mock MovieNoteRepository notes;
    @Mock SendMessageWebhookUseCase send;
    @Mock UpdateMessageWebhookUseCase updateWebhook;
    @Mock MovieSocketRespository socket;
    @InjectMocks CreateMovieUseCaseImpl create;
    @InjectMocks DeleteMovieUseCaseImpl delete;
    @InjectMocks SearchMovieUseCaseImpl search;

    private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID movieId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private User user(UUID id, String name) { return User.builder().id(id).name(name).email(name+"@example.test").build(); }
    private Movie movie() { return Movie.builder().id(movieId).tmdbId(42L).user(user(userId,"Alice"))
            .notes(new ArrayList<>()).createdAt(OffsetDateTime.now().minusMinutes(5)).build(); }
    private MovieNote note(Movie movie, UUID id, String name, Integer score) {
        return MovieNote.builder().movie(movie).user(user(id,name)).note(score).build();
    }
    private CreateMovieNoteUseCaseImpl createNote() {
        CreateMovieNoteUseCaseImpl useCase = new CreateMovieNoteUseCaseImpl();
        ReflectionTestUtils.setField(useCase, "repository", notes);
        ReflectionTestUtils.setField(useCase, "movieRepository", repository);
        ReflectionTestUtils.setField(useCase, "userRepository", userRepository);
        ReflectionTestUtils.setField(useCase, "updateMessageWebhookUseCase", updateWebhook);
        ReflectionTestUtils.setField(useCase, "movieSocketRespository", socket);
        return useCase;
    }

    @Test void duplicateTmdbIdPreventsWritesAndNotifications() {
        Movie m=movie(); when(repository.findByTmdbId(42L)).thenReturn(m);
        assertThatThrownBy(() -> create.save(m,userId)).isInstanceOf(DuplicatedMovieException.class);
        verify(repository,never()).saveAndFlush(any());
        verifyNoInteractions(userRepository,send,socket);
    }

    @Test void createPersistsHiddenMovieBeforeBestEffortNotifications() throws Exception {
        Movie m=movie(); m.setShowNotes(true); User u=m.getUser();
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));
        when(repository.saveAndFlush(m)).thenReturn(m);
        assertThat(create.save(m,userId)).isSameAs(m);
        assertThat(m.isShowNotes()).isFalse(); assertThat(m.getUser()).isSameAs(u);
        InOrder order=inOrder(userRepository,repository,send,socket);
        order.verify(repository).findByTmdbId(42L); order.verify(userRepository).findById(userId);
        order.verify(userRepository).saveAndFlush(u); order.verify(repository).saveAndFlush(m);
        order.verify(send).sendMovieMessage(movieId); order.verify(socket).emitAllMoviesEvent(null);
    }

    @Test void discordFailureStillAllowsSocketNotificationAndSuccessfulCreate() throws Exception {
        Movie m=movie(); when(userRepository.findById(userId)).thenReturn(Optional.of(m.getUser()));
        when(repository.saveAndFlush(m)).thenReturn(m);
        doThrow(new IllegalStateException("stub unavailable")).when(send).sendMovieMessage(movieId);
        assertThat(create.save(m,userId)).isSameAs(m);
        verify(socket).emitAllMoviesEvent(null);
    }

    @Test void persistenceFailurePreventsExternalNotifications() {
        Movie m=movie(); when(userRepository.findById(userId)).thenReturn(Optional.of(m.getUser()));
        when(repository.saveAndFlush(m)).thenThrow(new IllegalStateException("stub write failed"));
        assertThatThrownBy(() -> create.save(m,userId)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(send,socket);
    }

    @Test void deletingMissingMovieIsNoOp() throws Exception {
        delete.delete(movieId,userId); verifyNoInteractions(userRepository,socket);
        verify(repository,never()).delete(any(Movie.class));
    }

    @Test void ordinaryUserCannotDeleteOrphanMovie() {
        Movie m=movie(); m.setUser(null);
        when(repository.findById(movieId)).thenReturn(Optional.of(m));
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId,"Alice")));
        assertThatThrownBy(() -> delete.delete(movieId,userId)).isInstanceOf(UserNotAuthorizedException.class);
        verify(repository,never()).delete(any(Movie.class)); verifyNoInteractions(socket);
    }

    @Test void administratorCanDeleteOrphanMovieAndEventsCarryActor() throws Exception {
        Movie m=movie(); m.setUser(null); User u=user(userId,"Alice");u.addRole(Role.ADM);
        when(repository.findById(movieId)).thenReturn(Optional.of(m));
        when(userRepository.findById(userId)).thenReturn(Optional.of(u));
        delete.delete(movieId,userId);
        InOrder order=inOrder(repository,socket); order.verify(repository).findById(movieId);
        order.verify(repository).delete(m); order.verify(socket).emitAllMoviesEvent(null);
        order.verify(socket).emitEventMovie(movieId,"deleted-movie",null,userId.toString());
    }

    @Test void legacyDeleteRejectsOwnerWithDistinctEqualUuid() {
        Movie m=movie(); User detached=user(UUID.fromString(userId.toString()),"Alice");
        when(repository.findById(movieId)).thenReturn(Optional.of(m));
        when(userRepository.findById(userId)).thenReturn(Optional.of(detached));
        assertThatThrownBy(() -> delete.delete(movieId,userId)).isInstanceOf(UserNotAuthorizedException.class);
        verify(repository,never()).delete(any(Movie.class));
    }

    @Test void missingMovieReadUsesMovieNoteNotFoundException() {
        assertThatThrownBy(() -> search.getById(movieId,userId)).isInstanceOf(MovieNoteNotFoundException.class);
    }

    @Test void recentHiddenMovieSortsAlphabeticallyAndOnlyRevealsOwnScore() throws Exception {
        Movie m=movie(); MovieNote own=note(m,userId,"Zoe",8);
        MovieNote other=note(m,UUID.fromString("00000000-0000-0000-0000-000000000003"),"Alice",10);
        m.setNotes(new ArrayList<>(List.of(own,other))); when(repository.findById(movieId)).thenReturn(Optional.of(m));
        Movie result=search.getById(movieId,userId);
        assertThat(result.getNotes()).containsExactly(other,own);
        assertThat(own.getNote()).isEqualTo(8); assertThat(other.getNote()).isNull();
        verify(repository,never()).saveAndFlush(any());
    }

    @Test void oldHiddenMovieIsPersistentlyRevealedAndSortedByScore() throws Exception {
        Movie m=movie();m.setCreatedAt(OffsetDateTime.now().minusHours(1));
        MovieNote lower=note(m,userId,"Alice",7);MovieNote higher=note(m,UUID.fromString("00000000-0000-0000-0000-000000000099"),"Zoe",9);
        m.setNotes(new ArrayList<>(List.of(lower,higher))); when(repository.findById(movieId)).thenReturn(Optional.of(m));
        Movie result=search.getById(movieId,userId);
        assertThat(result.isShowNotes()).isTrue();assertThat(result.getNotes()).containsExactly(higher,lower);
        assertThat(higher.getNote()).isEqualTo(9);verify(repository).saveAndFlush(m);
    }

    @Test void listParsesFiltersPreservesPageMetadataAndDoesNotRevealOldHiddenMovie() {
        Movie m=movie();m.setCreatedAt(OffsetDateTime.now().minusHours(1));
        m.setNotes(new ArrayList<>(List.of(note(m,UUID.fromString("00000000-0000-0000-0000-000000000099"),"Other",9))));
        Pageable pageable=PageRequest.of(1,2);
        when(repository.findMovieFilteredCustom(List.of("The","Movie"),List.of(2L,3L),"genre",pageable))
                .thenReturn(new PageImpl<>(List.of(m),pageable,5));
        Page<Movie> result=search.listAllMovies("The Movie","2,3","genre",pageable,userId);
        assertThat(result.getTotalElements()).isEqualTo(5);assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getContent().getFirst().isShowNotes()).isFalse();
        assertThat(result.getContent().getFirst().getNotes().getFirst().getNote()).isNull();
        verify(repository,never()).saveAndFlush(any());
    }

    @Test void malformedGenreFailsBeforeQueryingRepository() {
        assertThatThrownBy(() -> search.listAllMovies(null,"invalid",null,PageRequest.of(0,10),userId))
                .isInstanceOf(NumberFormatException.class);verifyNoInteractions(repository);
    }

    @Test void missingMoviePreventsRatingWritesOrEffects() {
        when(userRepository.findByEmail("Alice@example.test")).thenReturn(user(userId,"Alice"));
        assertThatThrownBy(() -> createNote().create(movieId,8,"Alice@example.test"))
                .isInstanceOf(MovieNotFoundException.class);
        verifyNoInteractions(notes,updateWebhook,socket);
    }

    @Test void duplicateRatingPreventsWritesOrEffects() {
        Movie m=movie();MovieNote n=note(m,userId,"Alice",7);
        when(userRepository.findByEmail("Alice@example.test")).thenReturn(n.getUser());
        when(repository.findById(movieId)).thenReturn(Optional.of(m));
        when(notes.findById(new MovieNoteKey(userId,movieId))).thenReturn(Optional.of(n));
        assertThatThrownBy(() -> createNote().create(movieId,8,"Alice@example.test"))
                .isInstanceOf(DuplicatedMovieNoteException.class);
        verify(notes,never()).saveAndFlush(any());verifyNoInteractions(updateWebhook,socket);
    }

    @Test void hiddenRatingEventMasksCloneWithoutErasingSavedScore() throws Exception {
        Movie m=movie();User u=m.getUser();
        when(userRepository.findByEmail(u.getEmail())).thenReturn(u);
        when(repository.findById(movieId)).thenReturn(Optional.of(m));
        when(notes.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        MovieNote saved=createNote().create(movieId,8,u.getEmail());
        ArgumentCaptor<MovieNote> payload=ArgumentCaptor.forClass(MovieNote.class);
        verify(socket).emitEventMovie(eq(movieId),eq("create-note"),payload.capture(),isNull());
        assertThat(saved.getNote()).isEqualTo(8); assertThat(payload.getValue()).isNotSameAs(saved);
        assertThat(payload.getValue().getNote()).isNull();assertThat(payload.getValue().getId()).isEqualTo(saved.getId());
    }

    @Test void legacyNullRatingIsAcceptedByUseCase() throws Exception {
        Movie m=movie();User u=m.getUser();
        when(userRepository.findByEmail(u.getEmail())).thenReturn(u);
        when(repository.findById(movieId)).thenReturn(Optional.of(m));
        when(notes.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(createNote().create(movieId,null,u.getEmail()).getNote()).isNull();
        verify(notes).saveAndFlush(any());
    }
}
