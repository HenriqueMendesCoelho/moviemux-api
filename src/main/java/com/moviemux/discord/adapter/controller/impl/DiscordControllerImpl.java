package com.moviemux.discord.adapter.controller.impl;

import java.util.UUID;

import com.moviemux.discord.adapter.controller.DiscordController;
import com.moviemux.discord.usecase.SendMessageWebhookUseCase;
import com.moviemux.discord.usecase.UpdateMessageWebhookUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class DiscordControllerImpl implements DiscordController {

	private final SendMessageWebhookUseCase sendMessageWebhook;
	private final UpdateMessageWebhookUseCase updateMessageWebhook;

	@Override
	public void execute(UUID movieId) {
		sendMessageWebhook.sendMovieMessage(movieId);
	}

	@Override
	public void update(UUID movieId) {
		updateMessageWebhook.updateMovieMessage(movieId);
	}

}
