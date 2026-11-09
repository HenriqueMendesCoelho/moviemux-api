package com.moviemux.discord.adapter.repository.rest.dto;

import com.moviemux.discord.domain.model.DiscordWebhookInfo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DiscordWebhookResponseDto {
	private String id;

	public DiscordWebhookInfo toDomain() {
		return DiscordWebhookInfo.builder().messageId(id).build();
	}
}
