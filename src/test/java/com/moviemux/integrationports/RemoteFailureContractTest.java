package com.moviemux.integrationports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviemux.kronusintegrationtool.adapter.repository.rest.impl.KronusIntegrationToolRepositoryImpl;
import com.moviemux.kronusintegrationtool.domain.exception.IntegrationFailure;
import com.moviemux.kronusintegrationtool.domain.repository.EmailGateway;
import com.moviemux.kronusintegrationtool.domain.model.SendMailTemplate;
import com.moviemux.discord.adapter.repository.rest.impl.DiscordRepositoryImpl;
import com.moviemux.discord.domain.model.*;
import com.moviemux.movie.adapter.repository.rest.impl.MovieSocketRestRespositoryImpl;
import com.moviemux.movie.domain.repository.MovieNotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class RemoteFailureContractTest {
    private final AtomicInteger calls = new AtomicInteger();
    private KronusIntegrationToolRepositoryImpl kit(Mono<ClientResponse> response) {
        var client = WebClient.builder().exchangeFunction(request -> { calls.incrementAndGet(); return response; }).build();
        var repository = new KronusIntegrationToolRepositoryImpl();
        ReflectionTestUtils.setField(repository, "webClientKit", client);
        ReflectionTestUtils.setField(repository, "mapper", new ObjectMapper());
        return repository;
    }
    private Mono<ClientResponse> response(int status, String body) {
        return Mono.just(ClientResponse.create(HttpStatus.valueOf(status)).header("Content-Type", "application/json").body(body).build());
    }
    @ParameterizedTest @ValueSource(strings = {"", "not-json", "{}", "{\"id\":42,\"results\":null}"})
    void absentOrMalformedBodyIsInvalidResponseNotSuccessfulAbsence(String body) {
        assertThatThrownBy(() -> kit(response(200, body)).getWatchProviders(42L)).isInstanceOfSatisfying(IntegrationFailure.class,
                failure -> assertThat(failure.getKind()).isEqualTo(IntegrationFailure.Kind.INVALID_RESPONSE));
        assertThat(calls).hasValue(1);
    }
    @ParameterizedTest @ValueSource(ints = {404, 429, 500, 503})
    void remoteStatusIsRetainedWithoutTreating404AsEmpty(int status) {
        assertThatThrownBy(() -> kit(response(status, "{}")).getWatchProviders(42L)).isInstanceOfSatisfying(IntegrationFailure.class, failure -> {
            assertThat(failure.getKind()).isEqualTo(IntegrationFailure.Kind.REMOTE_STATUS);
            assertThat(failure.getStatus()).isEqualTo(status);
        });
        assertThat(calls).hasValue(1);
    }
    @Test void timeoutIsTechnicalFailureWithoutAutomaticRetry() {
        assertThatThrownBy(() -> kit(Mono.error(new TimeoutException("synthetic timeout"))).getWatchProviders(42L))
                .isInstanceOfSatisfying(IntegrationFailure.class, failure -> assertThat(failure.getKind()).isEqualTo(IntegrationFailure.Kind.TRANSPORT));
        assertThat(calls).hasValue(1);
    }
    @Test void emptyCatalogIsSuccessfulAndDistinctFromInvalidResponse() {
        assertThat(kit(response(200, "{\"id\":42,\"results\":{}}")).getWatchProviders(42L).getResults()).isEmpty();
    }
    @Test void emailDisabledIsDistinctFromSentWithoutEvaluatingPayload() {
        var repository = kit(response(200, ""));
        assertThat(repository.sendEmail(null)).isEqualTo(EmailGateway.Delivery.DISABLED);
        assertThat(calls).hasValue(0);
        ReflectionTestUtils.setField(repository, "sendMail", true);
        assertThat(repository.sendEmail(SendMailTemplate.builder().to("synthetic@example.test").build())).isEqualTo(EmailGateway.Delivery.SENT);
        assertThat(calls).hasValue(1);
    }
    @Test void discordDisabledAndMissingReferenceAreDifferentOutcomes() {
        var repository = new DiscordRepositoryImpl();
        assertThat(repository.send(null).status()).isEqualTo(WebhookDelivery.Status.DISABLED);
        assertThat(repository.edit(null).status()).isEqualTo(WebhookDelivery.Status.DISABLED);
        ReflectionTestUtils.setField(repository, "update", true);
        var movie = new WebhookMovie(UUID.randomUUID(), "Movie", null, true, null, null, null, List.of());
        assertThat(repository.edit(new com.moviemux.discord.domain.repository.WebhookMovieSource() {
            public boolean showNotes() { return movie.showNotes(); }
            public String messageId() { return movie.messageId(); }
            public WebhookMovie snapshot() { return movie; }
        }).status()).isEqualTo(WebhookDelivery.Status.MISSING_REFERENCE);
    }
    @Test void socketInvalidInputReturnsExplicitSkippedWithoutCallingClient() {
        var repository = new MovieSocketRestRespositoryImpl();
        assertThat(repository.notifyMovie(null, "update", null, null)).isEqualTo(MovieNotificationRepository.Delivery.SKIPPED);
        assertThat(repository.notifyMovie(UUID.randomUUID(), " ", null, null)).isEqualTo(MovieNotificationRepository.Delivery.SKIPPED);
    }
}
