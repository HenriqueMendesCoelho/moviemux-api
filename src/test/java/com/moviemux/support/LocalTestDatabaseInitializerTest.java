package com.moviemux.support;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalTestDatabaseInitializerTest {
    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://127.0.0.1:5432/moviemux_test", "jdbc:postgresql://localhost:15432/test_suite"})
    void acceptsOnlyDisposableLoopbackTargets(String url) {
        assertThatCode(() -> LocalTestDatabaseInitializer.requireLocalTestDatabase(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"jdbc:postgresql://database.example:5432/moviemux_test",
            "jdbc:postgresql://127.0.0.1:5432/moviemux",
            "jdbc:postgresql://127.0.0.1:5432/moviemux_test?socketFactory=other.Factory",
            "jdbc:postgresql://localhost.evil.example:5432/moviemux_test"})
    void rejectsOtherTargetsBeforeOpeningConnections(String url) {
        assertThatThrownBy(() -> LocalTestDatabaseInitializer.requireLocalTestDatabase(url))
                .isInstanceOf(IllegalStateException.class);
    }
}
