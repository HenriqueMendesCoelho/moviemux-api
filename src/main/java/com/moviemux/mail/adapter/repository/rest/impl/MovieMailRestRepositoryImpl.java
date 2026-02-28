package com.moviemux.mail.adapter.repository.rest.impl;

import com.moviemux.mail.adapter.repository.rest.MovieMailRestRepository;
import com.moviemux.mail.adapter.repository.rest.dto.SendMailTemplateRequestDto;
import com.moviemux.mail.domain.SendMailTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;

@Repository
@Slf4j
@RequiredArgsConstructor
public class MovieMailRestRepositoryImpl implements MovieMailRestRepository {

	private final RestClient restClientMail;

	@Override
	public void sendTemplateMail(SendMailTemplate sendMailTemplate) {
		try {
			restClientMail.post()
					.uri("/api/mail/send")
					.body(new SendMailTemplateRequestDto(sendMailTemplate))
					.retrieve()
					.toBodilessEntity();
		} catch (Exception e) {
			log.error("Error on send mail request raised: ", e);
			throw new RequestRejectedException(e.getMessage());
		}
	}
}
