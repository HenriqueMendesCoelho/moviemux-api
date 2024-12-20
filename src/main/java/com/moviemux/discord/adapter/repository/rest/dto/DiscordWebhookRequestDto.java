package com.moviemux.discord.adapter.repository.rest.dto;

import java.util.List;

import com.moviemux.movie.domain.Movie;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class DiscordWebhookRequestDto {

	public String content;
	public List<EmbedRequestDto> embeds;

	public DiscordWebhookRequestDto(Movie movie, String roleId) {
		content = roleId != null ? "<@&%s>".formatted(roleId) : "";
		embeds = List.of(new EmbedRequestDto(movie));
	}

}
