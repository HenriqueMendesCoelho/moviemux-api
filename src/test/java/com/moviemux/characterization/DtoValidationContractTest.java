package com.moviemux.characterization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.movie.adapter.controller.dto.MovieNoteRequestDto;
import com.moviemux.user.adapter.controller.dto.UserRequestDto;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class DtoValidationContractTest {
    private ValidatorFactory factory;
    @BeforeEach void setup() { factory = Validation.buildDefaultValidatorFactory(); }
    @AfterEach void close() { factory.close(); }
    @ParameterizedTest @NullSource @ValueSource(ints = {0, 10})
    void noteAcceptsCurrentNullAndInclusiveBoundaries(Integer note) {
        assertThat(factory.getValidator().validate(new MovieNoteRequestDto(UUID.randomUUID(), note))).isEmpty();
    }
    @ParameterizedTest @ValueSource(ints = {-1, 11})
    void noteRejectsOutOfRangeValues(Integer note) {
        assertThat(factory.getValidator().validate(new MovieNoteRequestDto(UUID.randomUUID(), note)))
                .singleElement().satisfies(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("note"));
    }
    @ParameterizedTest @ValueSource(strings = {"weak", "lowercase1!", "UPPERCASE1!", "MissingDigit!", "MissingSymbol1"})
    void registrationRejectsWeakPassword(String password) {
        var request = new UserRequestDto();
        request.setName("Synthetic User");
        request.setEmail("synthetic@example.test");
        request.setPassword(password);
        assertThat(factory.getValidator().validate(request)).isNotEmpty()
                .allSatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("password"));
    }
    @Test void noteJsonPreservesUuidAndNumericScore() throws Exception {
        var mapper = new ObjectMapper();
        var request = mapper.readValue("{\"movieId\":\"00000000-0000-0000-0000-000000000042\",\"note\":8}", MovieNoteRequestDto.class);
        assertThat(request.getMovieId()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000042"));
        assertThat(request.getNote()).isEqualTo(8);
        assertThat(mapper.readTree(mapper.writeValueAsString(request)).get("note").isNumber()).isTrue();
    }
}
