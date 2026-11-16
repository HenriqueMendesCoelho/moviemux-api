package com.moviemux.discord.adapter.controller.impl;

import java.util.UUID;

import com.moviemux.discord.adapter.controller.DiscordController;
import org.springframework.stereotype.Controller;

import com.moviemux.discord.domain.usecase.SendMessageWebhookUseCase;
import com.moviemux.discord.domain.usecase.UpdateMessageWebhookUseCase;

@Controller
public class DiscordControllerImpl implements DiscordController {

	private final SendMessageWebhookUseCase sendMessageWebhook;

	private final UpdateMessageWebhookUseCase updateMessageWebhook;

	public DiscordControllerImpl(SendMessageWebhookUseCase sendMessageWebhook, UpdateMessageWebhookUseCase updateMessageWebhook) {
		this.sendMessageWebhook = sendMessageWebhook;
		this.updateMessageWebhook = updateMessageWebhook;
	}

	@Override
	public void execute(UUID movieId) {
		sendMessageWebhook.execute(movieId);
	}

	@Override
	public void update(UUID movieId) {
		updateMessageWebhook.execute(movieId);
	}

}
