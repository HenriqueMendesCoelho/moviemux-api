package com.moviemux.kronusintegrationtool.adapter.repository.rest.dto;

import java.util.LinkedHashMap;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.moviemux.kronusintegrationtool.domain.model.SendMailTemplate;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class SendMailTemplateRequestDto {

	private String from;
	private String to;
	private String templateId;
	private String subject;
	private LinkedHashMap<String, String> params;

	public SendMailTemplateRequestDto(SendMailTemplate sendMailTemplate) {
		from = sendMailTemplate.getFrom();
		to = sendMailTemplate.getTo();
		templateId = sendMailTemplate.getTemplateId();
		subject = sendMailTemplate.getSubject();
		params = sendMailTemplate.getParams();
	}
}
