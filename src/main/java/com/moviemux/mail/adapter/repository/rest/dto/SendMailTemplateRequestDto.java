package com.moviemux.mail.adapter.repository.rest.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.moviemux.mail.domain.SendMailTemplate;
import lombok.*;

import java.util.LinkedHashMap;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SendMailTemplateRequestDto {

	private String to;
	private String templateId;
	private LinkedHashMap<String, String> metadata;

	public SendMailTemplateRequestDto(SendMailTemplate sendMailTemplate) {
		to = sendMailTemplate.getTo();
		templateId = sendMailTemplate.getTemplateId();
		metadata = sendMailTemplate.getParams();
	}
}
