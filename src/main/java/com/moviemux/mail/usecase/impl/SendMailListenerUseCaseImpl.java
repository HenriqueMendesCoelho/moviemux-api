package com.moviemux.mail.usecase.impl;

import com.moviemux.mail.adapter.repository.rest.MovieMailRestRepository;
import com.moviemux.mail.domain.SendMailEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SendMailListenerUseCaseImpl {

	@Value("${send.mail}")
	private boolean sendMail;

	private final MovieMailRestRepository movieMailRestRepository;

	@EventListener
	public void onSendMailEvent(SendMailEvent event) {
		if (!sendMail)
			return;
		movieMailRestRepository.sendTemplateMail(event.template());
	}
}
