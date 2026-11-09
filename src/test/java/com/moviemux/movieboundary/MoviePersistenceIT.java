package com.moviemux.movieboundary;

import com.moviemux.movie.domain.model.*;
import com.moviemux.movie.domain.repository.MovieRepository;
import com.moviemux.movie.domain.repository.MovieNoteRepository;
import com.moviemux.movie.domain.repository.MovieGenreRepository;
import com.moviemux.movie.domain.usecase.impl.GetMovieUseCaseImpl;
import com.moviemux.movie.adapter.repository.postgres.MoviePostgresRepository;
import com.moviemux.user.domain.model.Role;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL, migrated by Flyway; no test transaction hides detached/lazy behavior. */
@SpringBootTest
@ActiveProfiles("test")
class MoviePersistenceIT {
    private static final String SCHEMA = "movie_test_" + UUID.randomUUID().toString().replace("-", "");
    private static String url, username, password;

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry properties) throws Exception {
        url = System.getenv("TEST_DATABASE_URL");
        if (url == null || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/[a-zA-Z0-9_]*test[a-zA-Z0-9_]*(\\?.*)?")) {
            throw new IllegalStateException("An explicitly disposable loopback PostgreSQL test database is required");
        }
        username = System.getenv().getOrDefault("TEST_DATABASE_USER", "moviemux_test");
        password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "moviemux_test");
        try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.createStatement().execute("create schema " + SCHEMA);
        }
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @AfterAll static void removeOnlyOwnedSchema() throws Exception {
        if (url != null) try (var connection = DriverManager.getConnection(url, username, password)) {
            connection.createStatement().execute("drop schema " + SCHEMA + " cascade");
        }
    }

    @Autowired MovieRepository movies;
    @Autowired MovieNoteRepository notes;
    @Autowired MovieGenreRepository genres;
    @Autowired com.moviemux.movie.adapter.repository.MovieRepository legacyMovies;
    @Autowired JdbcTemplate jdbc;

    private MovieActor actor() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into \"user\"(id,name,email,password,created_at) values (?,?,?,?,?)", id,"Synthetic",id+"@example.test","synthetic-not-a-real-password",OffsetDateTime.now());
        jdbc.update("insert into \"user_roles\"(\"user_id\",roles) values (?,?)",id,Role.USER.getCode());
        return new MovieActor(id,"Synthetic",id+"@example.test",Set.of(Role.USER));
    }

    private Movie movie(MovieActor user, String title, List<MovieGenre> genres) {
        return movies.save(Movie.builder().portugueseTitle(title).englishTitle(title).originalTitle(title)
                .director("Synthetic Director").description("Synthetic description").urlImage("https://example.test/image")
                .runtime(0).showNotes(false).genres(genres).user(user).build());
    }

    @Test
    void detachedRoundTripPreservesIdsTimestampsChildrenAndDoesNotPersistHiddenProjection() throws Exception {
        MovieActor user = actor();
        MovieGenre genre = genres.save(MovieGenre.builder().name("Drama-"+UUID.randomUUID()).tmdbId(42L).build());
        Movie inserted = movie(user,"Detached",List.of(genre));
        assertThat(inserted.getId()).isNotNull();
        assertThat(inserted.getCreatedAt()).isNotNull();
        MovieNote savedNote = notes.save(MovieNote.builder().movie(inserted).user(user).note(9).build());
        assertThat(savedNote.getId()).isEqualTo(new MovieNoteKey(user.id(),inserted.getId()));
        assertThat(savedNote.getCreatedAt()).isNotNull();
        Movie loaded = movies.findById(inserted.getId()).orElseThrow();
        assertThat(loaded.getGenres()).extracting(MovieGenre::getId).containsExactly(genre.getId());
        assertThat(loaded.getNotes()).extracting(MovieNote::getNote).containsExactly(9);
        assertThat(loaded.getUser().name()).isEqualTo("Synthetic");
        var reader = new GetMovieUseCaseImpl(movies, Clock.fixed(loaded.getCreatedAt().plusSeconds(1799).toInstant(),ZoneOffset.UTC));
        Movie projection = reader.execute(inserted.getId(), UUID.randomUUID());
        assertThat(projection.getNotes().getFirst().getNote()).isNull();
        assertThat(jdbc.queryForObject("select note from movie_note where movie_id=?",Integer.class,inserted.getId())).isEqualTo(9);
        assertThat(movies.findById(inserted.getId()).orElseThrow().isShowNotes()).isFalse();
        var reveal = new GetMovieUseCaseImpl(movies, Clock.fixed(loaded.getCreatedAt().plusSeconds(1800).toInstant(),ZoneOffset.UTC));
        assertThat(reveal.execute(inserted.getId(),UUID.randomUUID()).getNotes().getFirst().getNote()).isEqualTo(9);
        assertThat(movies.findById(inserted.getId()).orElseThrow().isShowNotes()).isTrue();
        MovieNote updated = notes.findById(savedNote.getId()).orElseThrow();
        updated.setNote(10); notes.save(updated);
        assertThat(notes.findById(savedNote.getId()).orElseThrow().getNote()).isEqualTo(10);
        assertThat(notes.findById(savedNote.getId()).orElseThrow().getCreatedAt()).isEqualTo(savedNote.getCreatedAt());
        movies.delete(loaded);
        assertThat(movies.findById(inserted.getId())).isEmpty();
        assertThat(notes.findById(savedNote.getId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from \"user\" where id=?",Long.class,user.id())).isEqualTo(1L);
    }

    @Test
    void filtersCountsSortJoinAndPaginationMatchExistingJpaQuery() {
        MovieActor user = actor();
        String token = "Query"+UUID.randomUUID().toString().substring(0,8);
        MovieGenre genre = genres.save(MovieGenre.builder().name(token).build());
        movie(user,token+" Blue",List.of(genre));
        movie(user,token+" Red",List.of(genre));
        movie(user,token+" Other",List.of());
        var query = new MovieQuery(token,genre.getId().toString(),"notes,desc",0,1,false,
                List.of(new MovieQuery.Order("portugueseTitle",true,false,MovieQuery.NullHandling.NATIVE)));
        var expected = legacyMovies.findMovieFilteredCustom(List.of(token),List.of(genre.getId()),query.sortJoin(),MoviePostgresRepository.pageable(query));
        var actual = movies.findFiltered(List.of(token),List.of(genre.getId()),query);
        assertThat(actual.totalElements()).isEqualTo(expected.getTotalElements());
        assertThat(actual.content()).extracting(Movie::getId).containsExactlyElementsOf(expected.getContent().stream().map(com.moviemux.movie.adapter.repository.postgres.entity.MovieEntity::getId).toList());
        assertThat(actual.content().getFirst().getGenres()).extracting(MovieGenre::getId).containsExactly(genre.getId());
    }

    @Test
    void uniqueConstraintFailureRemainsFailureRatherThanAbsence() {
        String name = "Unique"+UUID.randomUUID();
        genres.save(MovieGenre.builder().name(name).build());
        assertThatThrownBy(() -> genres.save(MovieGenre.builder().name(name).build())).isInstanceOf(RuntimeException.class);
        assertThat(genres.findByName(name)).isNotNull();
    }
}
