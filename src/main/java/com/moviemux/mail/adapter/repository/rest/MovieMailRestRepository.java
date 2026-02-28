package com.moviemux.mail.adapter.repository.rest;

import com.moviemux.mail.domain.SendMailTemplate;

public interface MovieMailRestRepository {

	void sendTemplateMail(SendMailTemplate sendMailTemplate);

}
