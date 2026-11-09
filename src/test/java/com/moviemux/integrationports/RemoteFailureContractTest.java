package com.moviemux.integrationports;

import com.moviemux.kronusintegrationtool.adapter.repository.rest.impl.KronusIntegrationToolRepositoryImpl;
import com.moviemux.kronusintegrationtool.domain.exception.IntegrationFailure;
import com.moviemux.mail.domain.repository.EmailGateway;
import com.moviemux.mail.domain.model.SendMailTemplate;
import com.moviemux.discord.adapter.repository.rest.impl.DiscordRepositoryImpl;
import com.moviemux.discord.domain.model.*;
import com.moviemux.movie.adapter.repository.rest.impl.MovieSocketRestRespositoryImpl;
import com.moviemux.movie.domain.repository.MovieNotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import com.moviemux.mail.adapter.repository.rest.MovieMailRestRepository;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.UUID;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class RemoteFailureContractTest {
    private final AtomicInteger calls = new AtomicInteger();
    private RestClient client(ClientHttpRequestFactory requests) {
        return RestClient.builder().baseUrl("http://127.0.0.1:1").requestFactory(requests).build();
    }
    private KronusIntegrationToolRepositoryImpl kit(ClientHttpRequestFactory requests) {
        return new KronusIntegrationToolRepositoryImpl(client(requests));
    }
    private ClientHttpRequestFactory response(int status, String body) {
        return (uri, method) -> {
            calls.incrementAndGet();
            var request = new MockClientHttpRequest(method, uri);
            var response = new MockClientHttpResponse(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), HttpStatus.valueOf(status));
            response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            request.setResponse(response);
            return request;
        };
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
        assertThatThrownBy(() -> kit((uri, method) -> { calls.incrementAndGet(); throw new SocketTimeoutException("synthetic timeout"); }).getWatchProviders(42L))
                .isInstanceOfSatisfying(IntegrationFailure.class, failure -> assertThat(failure.getKind()).isEqualTo(IntegrationFailure.Kind.TRANSPORT));
        assertThat(calls).hasValue(1);
    }
    @Test void emptyCatalogIsSuccessfulAndDistinctFromInvalidResponse() {
        assertThat(kit(response(200, "{\"id\":42,\"results\":{}}")).getWatchProviders(42L).getResults()).isEmpty();
    }
    @Test void emailDisabledIsDistinctFromSentWithoutEvaluatingPayload() {
        var client = client(response(200, ""));
        var repository = new MovieMailRestRepository(client, false);
        assertThat(repository.sendEmail(null)).isEqualTo(EmailGateway.Delivery.DISABLED);
        assertThat(calls).hasValue(0);
        repository = new MovieMailRestRepository(client, true);
        assertThat(repository.sendEmail(new SendMailTemplate("synthetic@example.test", "welcome", java.util.Map.of()))).isEqualTo(EmailGateway.Delivery.SENT);
        assertThat(calls).hasValue(1);
    }
    @Test void discordDisabledAndMissingReferenceAreDifferentOutcomes() {
        var repository = new DiscordRepositoryImpl(null, false, false, "role");
        assertThat(repository.send(null).status()).isEqualTo(WebhookDelivery.Status.DISABLED);
        assertThat(repository.edit(null).status()).isEqualTo(WebhookDelivery.Status.DISABLED);
        repository = new DiscordRepositoryImpl(null, false, true, "role");
        var movie = new WebhookMovie(UUID.randomUUID(), "Movie", null, true, null, null, null, List.of());
        assertThat(repository.edit(new com.moviemux.discord.domain.repository.WebhookMovieSource() {
            public boolean showNotes() { return movie.showNotes(); }
            public String messageId() { return movie.messageId(); }
            public WebhookMovie snapshot() { return movie; }
        }).status()).isEqualTo(WebhookDelivery.Status.MISSING_REFERENCE);
    }
    @Test void socketInvalidInputReturnsExplicitSkippedWithoutCallingClient() {
        var repository = new MovieSocketRestRespositoryImpl(null);
        assertThat(repository.notifyMovie(null, "update", null, null)).isEqualTo(MovieNotificationRepository.Delivery.SKIPPED);
        assertThat(repository.notifyMovie(UUID.randomUUID(), " ", null, null)).isEqualTo(MovieNotificationRepository.Delivery.SKIPPED);
    }
}
