package com.moviemux.mail.adapter.repository.rest;

import com.moviemux.mail.adapter.repository.rest.dto.SendMailTemplateRequestDto;
import com.moviemux.mail.domain.exception.MailDeliveryFailure;
import com.moviemux.mail.domain.exception.MailDeliveryFailure.Reason;
import com.moviemux.mail.domain.model.SendMailTemplate;
import com.moviemux.mail.domain.repository.EmailGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Repository
public class MovieMailRestRepository implements EmailGateway {
  private final RestClient client;
  private final boolean enabled;

  public MovieMailRestRepository(
      @Qualifier("restClientMail") RestClient client, @Value("${send.mail}") boolean enabled) {
    this.client = client;
    this.enabled = enabled;
  }

  @Override
  public Delivery sendEmail(SendMailTemplate request) {
    if (!enabled) {
      return Delivery.DISABLED;
    }
    try {
      client.post()
          .uri("/api/mail/send")
          .body(SendMailTemplateRequestDto.from(request))
          .retrieve()
          .toBodilessEntity();
      return Delivery.SENT;
    } catch (RestClientResponseException failure) {
      throw new MailDeliveryFailure(Reason.HTTP_ERROR);
    } catch (ResourceAccessException failure) {
      throw new MailDeliveryFailure(Reason.UNAVAILABLE);
    } catch (RestClientException failure) {
      throw new MailDeliveryFailure(Reason.INVALID_RESPONSE);
    }
  }
}
