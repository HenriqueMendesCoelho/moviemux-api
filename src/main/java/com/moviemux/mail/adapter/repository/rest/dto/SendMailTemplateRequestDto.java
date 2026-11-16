package com.moviemux.mail.adapter.repository.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.moviemux.mail.domain.model.SendMailTemplate;
import java.util.Map;

public record SendMailTemplateRequestDto(
    String to, @JsonProperty("template_id") String templateId, Map<String, String> metadata) {
  public static SendMailTemplateRequestDto from(SendMailTemplate message) {
    return new SendMailTemplateRequestDto(message.to(), message.templateId(), message.metadata());
  }
}
