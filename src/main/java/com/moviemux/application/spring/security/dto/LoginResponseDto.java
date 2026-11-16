package com.moviemux.application.spring.security.dto;

import java.text.SimpleDateFormat;
import java.util.Date;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Setter
@Slf4j
public class LoginResponseDto {

	private String accessToken;
	private Date expires;

	public LoginResponseDto(String accessToken, Long expiration) {
		this.accessToken = accessToken;
		expires = new Date(new Date().getTime() + expiration);
	}

	public String toJson() {
		try {
			ObjectMapper mapper = getObjectMapper();
			return mapper.writeValueAsString(this);
		} catch (JsonProcessingException e) {
			log.error(e.getMessage());
		}
		return null;
	}

	private ObjectMapper getObjectMapper() {
		ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		mapper.setDateFormat(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"));
		return mapper;
	}
}
