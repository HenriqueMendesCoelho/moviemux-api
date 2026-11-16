package com.moviemux.integrationports;

import com.moviemux.application.spring.handler.SpringControllerAdviceHandler;
import com.moviemux.kronusintegrationtool.domain.exception.IntegrationFailure;
import com.moviemux.discord.domain.exception.WebhookFailure;
import com.moviemux.movie.domain.exception.NotificationFailure;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IntegrationHttpFailureTest {
    @RestController static class FailureEndpoint {
        @GetMapping("/test/integration/{kind}") String fail(@PathVariable String kind) {
            var cause = new IllegalStateException("synthetic private downstream detail");
            throw switch (kind) {
                case "catalog" -> new IntegrationFailure(IntegrationFailure.Kind.TRANSPORT, null, cause);
                case "webhook" -> new WebhookFailure(cause);
                default -> new NotificationFailure(cause);
            };
        }
    }
    @ParameterizedTest @ValueSource(strings = {"catalog", "webhook", "socket"})
    void independentExceptionsPreserveExistingPublicErrorContract(String kind) throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailureEndpoint())
                .setControllerAdvice(new SpringControllerAdviceHandler()).build();
        mvc.perform(get("/test/integration/" + kind)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("500"))
                .andExpect(jsonPath("$.error").value("internal server error"))
                .andExpect(jsonPath("$.message").value("contact the administrator"))
                .andExpect(jsonPath("$.path").value("/test/integration/" + kind))
                .andExpect(jsonPath("$.timestamp").isString());
    }
}
