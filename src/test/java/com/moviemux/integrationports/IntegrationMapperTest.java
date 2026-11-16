package com.moviemux.integrationports;

import com.moviemux.kronusintegrationtool.adapter.repository.rest.dto.*;
import com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper.*;
import com.moviemux.kronusintegrationtool.domain.exception.IntegrationFailure;
import com.moviemux.discord.adapter.repository.rest.mapper.WebhookRequestMapper;
import com.moviemux.discord.domain.model.WebhookMovie;
import com.moviemux.movie.adapter.repository.rest.mapper.NotificationMapper;
import com.moviemux.movie.domain.model.NotificationNote;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class IntegrationMapperTest {
    @Test void providerMapperPreservesEmptyVersusAbsentListsAndAllFields() {
        var provider = new WatchProvidersResponseDto.ProviderResponseDto("/logo", 7L, "Provider", 2);
        var country = new WatchProvidersResponseDto.CountryResponseDto("link", List.of(provider), List.of(), null);
        var result = WatchProvidersMapper.toDomain(new WatchProvidersResponseDto(42L, Map.of("BR", country)));
        assertThat(result.getId()).isEqualTo(42);
        assertThat(result.getResults().get("BR").getRent()).isEmpty();
        assertThat(result.getResults().get("BR").getBuy()).isNull();
        assertThat(result.getResults().get("BR").getFlatrate()).singleElement().satisfies(value -> {
            assertThat(value.getProviderId()).isEqualTo(7L); assertThat(value.getProviderName()).isEqualTo("Provider");
            assertThat(value.getLogoPath()).isEqualTo("/logo"); assertThat(value.getDisplayPriority()).isEqualTo(2);
        });
    }
    @Test void malformedProviderShapeIsNotSilentlyAnEmptyCatalog() {
        assertThatThrownBy(() -> WatchProvidersMapper.toDomain(new WatchProvidersResponseDto(42L, null))).isInstanceOf(NullPointerException.class);
    }
    @Test void genreMappingUsesCatalogValueNotMovieEntity() {
        var result = CatalogResponseMapper.genre(new MovieGenreResponseDto(18L, "Drama"));
        assertThat(result.id()).isEqualTo(18L); assertThat(result.name()).isEqualTo("Drama");
    }
    @Test void failureMappingRetainsStatusButDoesNotExposeBodyAsPublicMessage() {
        var failure = IntegrationFailureMapper.map(HttpClientErrorException.create(org.springframework.http.HttpStatus.NOT_FOUND, "Not found", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null));
        assertThat(failure.getKind()).isEqualTo(IntegrationFailure.Kind.REMOTE_STATUS);
        assertThat(failure.getStatus()).isEqualTo(404);
        assertThat(failure.getMessage()).isEqualTo("External integration failed");
    }
    @Test void malformedAndTransportFailuresAreDistinct() {
        assertThat(IntegrationFailureMapper.map(new HttpMessageNotReadableException("bad body", new MockHttpInputMessage(new byte[0]))).getKind()).isEqualTo(IntegrationFailure.Kind.INVALID_RESPONSE);
        assertThat(IntegrationFailureMapper.map(new java.util.concurrent.TimeoutException()).getKind()).isEqualTo(IntegrationFailure.Kind.TRANSPORT);
    }
    @Test void existingTypedFailureIsNotWrappedOrReclassified() {
        var existing = new IntegrationFailure(IntegrationFailure.Kind.TRANSPORT, null, new java.io.IOException());
        assertThat(IntegrationFailureMapper.map(existing)).isSameAs(existing);
    }
    @Test void nestedConversionFailureIsInvalidResponseWhileTransportRemainsTechnical() {
        var malformed = new HttpMessageNotReadableException("bad JSON", new MockHttpInputMessage(new byte[0]));
        var wrapped = new org.springframework.web.client.RestClientException("response decoding failed", malformed);
        assertThat(IntegrationFailureMapper.map(wrapped).getKind()).isEqualTo(IntegrationFailure.Kind.INVALID_RESPONSE);
        var transport = new org.springframework.web.client.ResourceAccessException("connection failed", new java.io.IOException());
        assertThat(IntegrationFailureMapper.map(transport).getKind()).isEqualTo(IntegrationFailure.Kind.TRANSPORT);
    }
    @Test void webhookPayloadPreservesRoundingOrderAndUnknownUserFooter() {
        var movie = new WebhookMovie(UUID.fromString("00000000-0000-0000-0000-000000000042"), "Movie", "/image", true, null,
                OffsetDateTime.parse("2025-01-01T12:00:00Z"), "message", List.of(new WebhookMovie.Rating("Zoe", 7), new WebhookMovie.Rating("Ana", 9)));
        var request = WebhookRequestMapper.toRequest(movie, "role");
        assertThat(request.getContent()).isEqualTo("<@&role>");
        var embed = request.getEmbeds().getFirst();
        assertThat(embed.getTitle()).isEqualTo("Movie | Nota: 8.0");
        assertThat(embed.getUrl()).isEqualTo("https://www.moviemux.com/movie/" + movie.id());
        assertThat(embed.getFooter().getText()).isEqualTo("🔴 Cadastrado por: Usuário desconhecido");
        assertThat(embed.getFields()).extracting(field -> field.getName()).containsExactly("Ana", "Zoe");
        assertThat(embed.getTimestamp()).isEqualTo(movie.createdAt());
        assertThat(embed.getImage().getUrl()).isEqualTo("/image");
    }
    @Test void socketMappingPreservesZeroAndIdentityWithoutPersistenceReferences() {
        var id = UUID.randomUUID(); var now = OffsetDateTime.parse("2025-01-01T12:00:00Z");
        var result = NotificationMapper.toRequest(new NotificationNote(0, now, now, id, "Viewer"));
        assertThat(result.getNote()).isZero(); assertThat(result.getCreatedAt()).isEqualTo(now);
        assertThat(result.getUpdatedAt()).isEqualTo(now); assertThat(result.getUser().getId()).isEqualTo(id);
        assertThat(result.getUser().getName()).isEqualTo("Viewer");
    }
}
