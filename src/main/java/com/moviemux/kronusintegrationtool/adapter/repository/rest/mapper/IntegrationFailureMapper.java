package com.moviemux.kronusintegrationtool.adapter.repository.rest.mapper;

import com.moviemux.kronusintegrationtool.domain.exception.IntegrationFailure;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

public final class IntegrationFailureMapper {
    private IntegrationFailureMapper() { }

    public static IntegrationFailure map(Exception failure) {
        if (failure instanceof IntegrationFailure typed) return typed;
        if (failure instanceof RestClientResponseException response)
            return new IntegrationFailure(IntegrationFailure.Kind.REMOTE_STATUS, response.getStatusCode().value(), failure);
        if (failure instanceof UnknownContentTypeException || failure instanceof IllegalArgumentException
                || failure instanceof NullPointerException || hasConversionFailure(failure))
            return new IntegrationFailure(IntegrationFailure.Kind.INVALID_RESPONSE, null, failure);
        return new IntegrationFailure(IntegrationFailure.Kind.TRANSPORT, null, failure);
    }

    private static boolean hasConversionFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpMessageConversionException) return true;
        }
        return false;
    }
}
