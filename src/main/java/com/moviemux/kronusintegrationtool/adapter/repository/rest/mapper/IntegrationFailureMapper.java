package com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper;

import com.moviemux.kronusintegrationtool.domain.exception.IntegrationFailure;
import org.springframework.core.codec.DecodingException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

public final class IntegrationFailureMapper {
    private IntegrationFailureMapper() { }
    public static IntegrationFailure map(Exception failure) {
        if (failure instanceof IntegrationFailure typed) return typed;
        if (failure instanceof WebClientResponseException response)
            return new IntegrationFailure(IntegrationFailure.Kind.REMOTE_STATUS, response.getStatusCode().value(), failure);
        if (failure instanceof DecodingException || failure instanceof IllegalArgumentException
                || failure instanceof NullPointerException)
            return new IntegrationFailure(IntegrationFailure.Kind.INVALID_RESPONSE, null, failure);
        return new IntegrationFailure(IntegrationFailure.Kind.TRANSPORT, null, failure);
    }
}
