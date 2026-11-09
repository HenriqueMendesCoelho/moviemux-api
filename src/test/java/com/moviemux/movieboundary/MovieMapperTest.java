package com.moviemux.movieboundary;

import com.moviemux.movie.adapter.controller.dto.MovieRequestDto;
import com.moviemux.movie.adapter.controller.mapper.MovieHttpMapper;
import com.moviemux.movie.adapter.repository.postgres.MoviePostgresRepository;
import com.moviemux.movie.adapter.repository.postgres.mapper.MovieEntityMapper;
import com.moviemux.movie.domain.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class MovieMapperTest {
    @Test
    void entityProjectionPreservesScalarsChildrenCompositeKeysAndDetachedState() {
        UUID userId = UUID.randomUUID(), movieId = UUID.randomUUID();
        var user = com.moviemux.user.adapter.repository.postgres.entity.UserEntity.builder().id(userId).name("Ada").email("ada@example.test").build();
        var created = OffsetDateTime.parse("2020-01-02T03:04:05Z");
        var entity = com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity.builder().id(movieId).portugueseTitle("PT").englishTitle("EN")
                .originalTitle("Original").director("Director").urlImage("image").description("Description")
                .portugueseUrlTrailer("pt-trailer").englishUrlTrailer("en-trailer").releaseDate(LocalDate.of(2020,1,1))
                .runtime(120).tmdbId(42L).imdbId("tt42").showNotes(true).createdAt(created).updatedAt(created.plusDays(1)).user(user)
                .genres(List.of(com.moviemux.movie.adapter.repository.postgres.entity.MovieGenreEntity.builder().id(7L).name("Drama").tmdbId(18L).build())).build();
        var note = com.moviemux.movie.adapter.repository.postgres.entity.MovieNoteEntity.builder().movie(entity).user(user).note(9).createdAt(created).updatedAt(created.plusHours(1)).build();
        entity.setNotes(new ArrayList<>(List.of(note)));
        entity.setMovieDiscord(new com.moviemux.movie.adapter.repository.postgres.entity.MovieDiscordEntity(movieId,"synthetic-message",entity));
        Movie model = MovieEntityMapper.movie(entity);
        assertThat(model.getId()).isEqualTo(movieId);
        assertThat(model.getEnglishTitle()).isEqualTo("EN");
        assertThat(model.getGenres()).extracting(MovieGenre::getId).containsExactly(7L);
        assertThat(model.getMovieDiscord().getMessageId()).isEqualTo("synthetic-message");
        assertThat(model.getNotes().getFirst().getId()).isEqualTo(new MovieNoteKey(userId,movieId));
        assertThat(model.getNotes().getFirst().getCreatedAt()).isEqualTo(created);
        assertThat(model.getNotes().getFirst().getUpdatedAt()).isEqualTo(created.plusHours(1));
        assertThat(model.getNotes().getFirst().getUser().getName()).isEqualTo("Ada");
        model.getNotes().getFirst().setNote(null);
        assertThat(note.getNote()).isEqualTo(9);
        var rebuilt = new com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity();
        MovieEntityMapper.copyScalars(model,rebuilt);
        assertThat(rebuilt).usingRecursiveComparison().ignoringFields("notes","genres","user","movieDiscord").isEqualTo(entity);
    }

    @Test
    void httpRequestMapsToCommandWithoutCreatingPersistenceEntities() {
        var request = new MovieRequestDto("PT","EN","Original","Director","image","pt","en","Description",List.of(7L),LocalDate.of(2020,1,1),42L,"tt42",0,true);
        UUID actor = UUID.randomUUID();
        var command = MovieHttpMapper.create(request,actor);
        assertThat(command.userId()).isEqualTo(actor);
        assertThat(command.movie().getEnglishTitle()).isEqualTo("EN");
        assertThat(command.movie().getGenres()).extracting(MovieGenre::getId).containsExactly(7L);
        assertThat(command.movie().getRuntime()).isZero();
        assertThat(command.movie().isShowNotes()).isTrue();
        assertThat(command.movie().getId()).isNull();
    }

    @Test
    void pageableRoundTripPreservesSortFlagsNullHandlingAndPageMetadata() {
        Pageable pageable = PageRequest.of(2,3,Sort.by(Sort.Order.desc("portugueseTitle").ignoreCase().nullsLast()));
        MovieQuery query = MovieHttpMapper.query("The Movie","1,2","notes,desc",pageable);
        assertThat(MoviePostgresRepository.pageable(query)).isEqualTo(pageable);
        var page = MovieHttpMapper.page(new MoviePage(List.of(),11,query),pageable);
        assertThat(page.getNumber()).isEqualTo(2);
        assertThat(page.getSize()).isEqualTo(3);
        assertThat(page.getTotalElements()).isEqualTo(11);
        assertThat(page.getTotalPages()).isEqualTo(4);
        assertThat(page.getSort()).isEqualTo(pageable.getSort());
    }

    @Test
    void unpagedRequestRemainsUnpaged() {
        Pageable pageable = Pageable.unpaged(Sort.by("id"));
        assertThat(MoviePostgresRepository.pageable(MovieHttpMapper.query(null,null,null,pageable))).isEqualTo(pageable);
    }
}
