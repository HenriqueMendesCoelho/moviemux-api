package com.moviemux.adapter.core.controller.dto;

import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class UserTokenDto {

	private UUID id;
	private String name;
	private Set<String> roles;

	@JsonProperty("sub")
	private String login;
	@JsonProperty("exp")
	private Long expiration;
	@JsonProperty("aud")
	private Set<String> audience;
}
